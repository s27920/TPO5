/**
 *
 *  @author Kubisa Jan S27920
 *
 */

package zad1;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer {
	public static final CountDownLatch serverReady = new CountDownLatch(1);
	public static final String delimiter = "\uD83D\uDE31";

	private final String host;
	private final int port;
	private final ExecutorService executorService;
	private final ByteBuffer communicationBuffer;
	private final StringBuilder serverLog;
	private final List<SocketChannel> activeClients;
	private final Map<SocketChannel, String> idClientBySocket;
	private final Map<SocketChannel, String> queuedClientAnswer;
	private final Map<SocketChannel, String> incompleteMessageCache;

	public ChatServer(String host, int port) {
		this.host = host;
		this.port = port;
		this.communicationBuffer = ByteBuffer.allocateDirect(1024);
		this.executorService = Executors.newSingleThreadExecutor();
		this.serverLog = new StringBuilder();
		this.idClientBySocket = new ConcurrentHashMap<>();
		this.activeClients = new CopyOnWriteArrayList<>();
		this.queuedClientAnswer = new ConcurrentHashMap<>();
		this.incompleteMessageCache = new ConcurrentHashMap<>();
	}

	public void startServer(){
		executorService.submit(()-> {
			try(ServerSocketChannel server = ServerSocketChannel.open();
				Selector selector = Selector.open()){
				configureServer(server, selector);
				while (!Thread.currentThread().isInterrupted()){
					serviceConnections(server, selector);
				}
			} catch (IOException e) {
				System.out.println("Server error\n");
				e.printStackTrace();
			}
		});
	}

	public void stopServer(){
		if (!activeClients.isEmpty()) {
			for (int i = activeClients.size()-1; i >= 0; i--){
				SocketChannel client = activeClients.get(i);
				activeClients.remove(client);
				logoutHandler(client);
			}
		}
		executorService.shutdown();
		try {
			if (!executorService.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
				executorService.shutdownNow();
			}
		} catch (InterruptedException e) {
			executorService.shutdownNow();
			Thread.currentThread().interrupt();
		}
		System.out.println("Server stopped");
	}

	private void configureServer(ServerSocketChannel server, Selector selector) throws IOException{
		server.socket().bind(new InetSocketAddress(host, port));
		server.configureBlocking(false);
		server.register(selector, SelectionKey.OP_ACCEPT);
		serverReady.countDown();
		System.out.println("Server started\n");
	}

	private void serviceConnections(ServerSocketChannel server, Selector selector) throws IOException {
		if (selector.select() > 0){
			Set<SelectionKey> keys = selector.selectedKeys();
			Iterator<SelectionKey> selectionKeyIterator = keys.iterator();
			while (selectionKeyIterator.hasNext()) {
				SelectionKey selectionKey = selectionKeyIterator.next();
				selectionKeyIterator.remove();
				if (selectionKey.isAcceptable()) {
					SocketChannel client = server.accept();
					if (client != null) {
						client.configureBlocking(false);
						client.register(selector, SelectionKey.OP_READ);
					}
				}else if (!executorService.isShutdown() && selectionKey.isReadable()) {
					SocketChannel client = (SocketChannel) selectionKey.channel();
					try {
						handleRead(client);
					}catch (EOFException | CancelledKeyException e){
						exceptionHandler(client, "connection closed by client. Exiting");
					}catch (SocketTimeoutException e){
						exceptionHandler(client, "client timed out. Exiting");
					}catch (SocketException e){
						exceptionHandler(client, "network error. Exiting");
					}catch (IOException e){
						e.printStackTrace();
					}
				}
			}
		}
	}

	private void handleRead(SocketChannel client) throws IOException {
		communicationBuffer.clear();
		while (client.read(communicationBuffer) > 0) {
			communicationBuffer.flip();
			byte[] bytes = new byte[communicationBuffer.limit()];
			communicationBuffer.get(bytes);
			String newMessage = new String(bytes);
			String existingData = incompleteMessageCache.getOrDefault(client, "");
			String combined = newMessage + (!existingData.equals("null")? existingData:"");
			if (combined.endsWith(delimiter)){
				processRequests(combined, client);
				incompleteMessageCache.put(client, "null");
			}else {
				incompleteMessageCache.put(client, combined);
			}
			communicationBuffer.clear();
		}
	}

	private void finishHandshake(String request, SocketChannel client){
		String[] reqParts = request.split(" ");
		if (reqParts[0].equals("ACK") && reqParts.length == 2) {
			loginHandler(request, client);
			queuedClientAnswer.put(client, "null");
		}
	}

	private void processRequests(String requests, SocketChannel client){
		for (String request : requests.split(delimiter)) {
			if (queuedClientAnswer.getOrDefault(client, "").equals(request)){
				finishHandshake(request, client);
			}else {
				serviceRequest(request, client);
			}
		}
	}

	private void serviceRequest(String request, SocketChannel client){
		String[] reqParts = request.split(" ");
		if (reqParts.length > 0){
			if (reqParts[0].equals("SYN")&& reqParts.length == 2) {
				respond("SYN " + reqParts[1] + " ACK", client);
				queuedClientAnswer.put(client, "ACK " + reqParts[1]);
			} else if (reqParts[0].equals("SYN") && reqParts[1].equals("RST") && reqParts.length == 3) {
				logoutHandler(client);
			} else {
				messageHandler(request, client);
			}
		}
	}

	private void messageHandler(String message, SocketChannel client){
		String name = getName(client);
		globalRespond(message, name);
		buildLog(message, name);
	}

	private void loginHandler(String request, SocketChannel client){
		try {
			activeClients.add(client);
			String name = request.replace("ACK ", "");
			String login_ = name + "\t" + client.getLocalAddress().toString();
			if (!idClientBySocket.containsKey(client)){
				idClientBySocket.put(client, login_);
			}
			globalLogInOutNotify("in", getName(client));
		} catch (IOException e) {
			exceptionHandler(client, "Could not retrieve client address. Exiting");
		}
	}

	private void logoutHandler(SocketChannel client){
		String name = getName(client);
		globalLogInOutNotify("out", name);
		if (activeClients.contains(client)) {
			activeClients.remove(client);
			respond("SYN RST " + name + " ACK", client);
		}
	}

	private void globalRespond(String message, String name){
		for (SocketChannel clientToContact : activeClients) {
			respond(name + ": " + message, clientToContact);
		}
	}

	private void globalLogInOutNotify(String inOut, String name){
		serverLog.append(LocalTime.now()).append(" ").append(name).append(" logged ").append(inOut).append("\n");
		for (SocketChannel clientToContact : activeClients) {
			respond(name + " logged " + inOut, clientToContact);
		}
	}

	private void exceptionHandler(SocketChannel client, String message){
		if (client != null) {
			try {
				client.close();
			} catch (IOException e) {
				System.out.println("Could not close socket");
			}
		}
		activeClients.remove(client);
		System.out.println(message);
	}

	private void respond(String response, SocketChannel client){
		ByteBuffer resp = StandardCharsets.UTF_8.encode(CharBuffer.wrap(response + delimiter));
		while (resp.hasRemaining()){
			try {
				client.write(resp);
			}catch (EOFException e){
				exceptionHandler(client, "connection closed by client. Exiting");
			}catch (SocketTimeoutException e){
				exceptionHandler(client, "client timed out. Exiting");
			}catch (SocketException e){
				exceptionHandler(client, "network error. Exiting");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private String getName(SocketChannel client) {
		return idClientBySocket.get(client).split("\t")[0];
	}

	private void buildLog(String message,String name){
		serverLog.append(LocalTime.now()).append(" ").append(name).append(": ").append(message).append("\n");
	}

	public StringBuilder getServerLog() {
		return serverLog;
	}

}
