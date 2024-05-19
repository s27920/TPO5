/**
 *
 *  @author Kubisa Jan S27920
 *
 */

package zad1;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ChatClientTask implements Runnable {
    private final ChatClient client;
    private final List<String> requests;
    private final int wait;
    private final CompletableFuture<String> futureTask;

    private ChatClientTask(ChatClient client, List<String> requests, int wait) {
        this.client = client;
        this.requests = requests;
        this.wait = wait;
        this.futureTask = new CompletableFuture<>();
    }

    public static ChatClientTask create(ChatClient c, List<String> msgs, int wait){
        return new ChatClientTask(c, msgs, wait);
    }

    @Override
    public void run() {
        try {
            ChatServer.serverReady.await();
            client.login();
            client.handleReadChat(wait);
            for (String request : requests) {
                client.send(request);
                client.handleReadChat(wait);
            }
            client.logout();
            Thread.sleep(wait);
            futureTask.complete(client.getChatView());
        }catch (InterruptedException | IOException e){
            Thread.currentThread().interrupt();
            client.close();
        }
    }

    public String get() throws InterruptedException, ExecutionException {
        try {
            return futureTask.get(3000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            e.printStackTrace();
            System.out.println("THIS IS THE ISSUE");
        }
        return "THIS IS THE ISSUE !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" + client.getChatView();
    }

    public ChatClient getClient() {
        return client;
    }
}
