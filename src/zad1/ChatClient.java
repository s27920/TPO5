/**
 *
 *  @author Kubisa Jan S27920
 *
 */

package zad1;


import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class ChatClient {
    static public final int MAX_ATTEMPTS = 20;

    private final String host;
    private final int port;
    private final String id;
    private SocketChannel socketChannel;
    private final ByteBuffer communicationBuffer;
    private final StringBuilder chatView;

    public ChatClient(String host, int port, String id) {
        this.host = host;
        this.port = port;
        this.id = id;
        this.communicationBuffer = ByteBuffer.allocateDirect(1024);
        this.chatView = new StringBuilder("=== ").append(id).append(" chat view").append("\n");
    }

    public void login(){
        int attempts = 0;
        while (attempts < MAX_ATTEMPTS) {
            try {
                socketChannel = SocketChannel.open();
                socketChannel.bind(null);
                socketChannel.connect(new InetSocketAddress(host, port));
                socketChannel.configureBlocking(false);
                if (sendCommand("SYN " + id) == 2){
                    send("ACK " + id);
                    return;
                }
                attempts++;
            } catch (ConnectException e) {
                attempts++;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        System.out.println("Server couldn't be reached. Aborting connection");
        close();
    }

    public void logout() {
        int attempts = 0;
        while(attempts < MAX_ATTEMPTS){
            if (sendCommand("SYN RST " + id) == 1){
                close();
                return;
            }
            attempts++;
        }
        System.out.println("Server unresponsive, failed logout.");
    }

    public int sendCommand(String req) {
        send(req);
        return processResponse(handleAwaitResponse());
    }

    public void sendMessage(String message){
        send(message);
    }

    private void send(String text){
        try {
            communicationBuffer.clear();
            communicationBuffer.put((text + ChatServer.delimiter).getBytes(StandardCharsets.UTF_8));
            communicationBuffer.flip();
            while (communicationBuffer.hasRemaining()) {
                socketChannel.write(communicationBuffer);
            }
            communicationBuffer.clear();
        }catch (SocketException e){
            System.out.println("ERROR: " + e.getMessage());
            close();
        }catch (ClosedChannelException e){
            System.out.println("Connection interrupted, exiting.");
            close();
        }catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private String handleAwaitResponse(){
        try(Selector selector = Selector.open()){
            socketChannel.register(selector, SelectionKey.OP_READ);
            while (selector.select() == 0);
            return middleman(selector);
        } catch (IOException e) {
            return "internal server issue idk";
        }
    }

    public void handleReadChat(int wait) throws IOException, InterruptedException{
        if (wait == 0){
            read();
            return;
        }
        try(Selector selector = Selector.open()) {
            socketChannel.register(selector, SelectionKey.OP_READ);
            long check = System.currentTimeMillis();
            if (selector.select(wait) == 0){
                return;
            }
            long passed = System.currentTimeMillis() - check;
            if (passed < wait){
                Thread.sleep(wait - passed);
            }
            processResponse(middleman(selector));
        }
    }

    private String middleman(Selector selector) throws IOException {
        if (selector.selectedKeys().iterator().next().isReadable()) {
            return read();
        }
        return "";
    }

    public String read() throws IOException {
        StringBuilder response = new StringBuilder();
        communicationBuffer.clear();
        while (socketChannel.read(communicationBuffer) > 0) {
            communicationBuffer.flip();
            response.append(StandardCharsets.UTF_8.decode(communicationBuffer));
            communicationBuffer.clear();
        }
        return response.toString();
    }

    private int processResponse(String wholeResponse){
        String[] responses = wholeResponse.split("\uD83D\uDE31");
        int controlAction = -1;
        for (String response : responses) {
            String[] parts = response.split(" ");
            if (parts[0].equals("SYN") && parts[1].equals("RST") && parts[parts.length - 1].equals("ACK") && response.contains(id)) {
                controlAction = 1;
            } else if (parts[0].equals("SYN") && parts[parts.length-1].equals("ACK")) {
                controlAction = 2;
            } else {
                if (!response.isEmpty()){
                    chatView.append(response).append("\n");
                }
            }
        }
        return controlAction;
    }

    public void close() {
        try {
            if (socketChannel != null) {
                socketChannel.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getChatView(){
        return chatView.toString();
    }

}
