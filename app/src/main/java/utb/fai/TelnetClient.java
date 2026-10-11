package utb.fai;

import java.io.*;
import java.net.*;

public class TelnetClient {

    private String serverIp;
    private int port;

    public TelnetClient(String serverIp, int port) {
        this.serverIp = serverIp;
        this.port = port;
    }

    public void run() {
        try {
            Socket socket = new Socket(serverIp, port);
            // Implementation of receiving and sending data
            // Implement processing of input from the user and sending data to the server
            // Implement response processing from the server and output to the console
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}