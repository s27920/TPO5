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
    static public final int MAX_ATTEMPTS = 5;

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
                for (String response : send("SYN " + id)) {
                    if (response.equals("SYN " + id + " ACK")) {
                        send("ACK " + id);
                        return;
                    }
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
            for (String response : send("SYN RST " + id)) {
                String[] parts = response.split(" ");
                if (parts[0].equals("SYN") && parts[1].equals("RST") && parts[parts.length - 1].equals("ACK") && response.contains(id)) {
                    close();
                    return;
                }
            }
            attempts++;
        }
        System.out.println("Server unresponsive, failed logout.");
    }

    public String[] send(String req) {
        try {
            communicationBuffer.clear();
            communicationBuffer.put(req.getBytes(StandardCharsets.UTF_8));
            communicationBuffer.flip();
            while (communicationBuffer.hasRemaining()) {
                socketChannel.write(communicationBuffer);
            }
            communicationBuffer.clear();
            return handleReadResponse();
        }catch (SocketException e){
            System.out.println("ERROR: " + e.getMessage());
            close();
        }catch (ClosedChannelException e){
            System.out.println("Connection interrupted, exiting.");
            close();
        }catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new String[]{};
    }

    private String[] handleReadResponse() throws IOException{
        try(Selector selector = Selector.open()){
            socketChannel.register(selector, SelectionKey.OP_READ);
            while (selector.select() == 0);
            return middleman(selector);
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
            middleman(selector);
        }
    }

    private String[] middleman(Selector selector) throws IOException {
        if (selector.selectedKeys().iterator().next().isReadable()) {
            return read();
        }
        return new String[]{};
    }

    public String[] read() throws IOException {
        StringBuilder response = new StringBuilder();
        communicationBuffer.clear();
        while (socketChannel.read(communicationBuffer) > 0) {
            communicationBuffer.flip();
            response.append(StandardCharsets.UTF_8.decode(communicationBuffer));
            communicationBuffer.clear();
        }
        return processResponse(response.toString());
    }

    private String[] processResponse(String wholeResponse){
        String[] responses = wholeResponse.split("\uD83D\uDE31");
        for (String response : responses) {
            String[] split = response.split(" ");
            if (!(split[0].equals("SYN") && split[split.length-1].equals("ACK"))) {
                if (!response.isEmpty()){
                    chatView.append(response).append("\n");
                }
            }
        }
        return responses;
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
