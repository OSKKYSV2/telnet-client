package utb.fai;

public class App {

    public static void main(String[] args) {
        // TODO: Implement input parameter processing

        TelnetClient telnetClient = new TelnetClient("127.0.0.1", 23);
        telnetClient.run(); // run telnet client
    }
}