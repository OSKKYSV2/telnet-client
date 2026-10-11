package utb.fai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Jednoduchý telnet klient.
 *
 * Program běží ve dvou nezávislých vláknech:
 * - vlákno "sender" čte klávesnici (System.in) a posílá řádky na server,
 * - vlákno "receiver" čte odpovědi ze serveru a vypisuje je na System.out.
 *
 * Obě vlákna se při čekání na data nikdy nezablokují: nejdřív se zeptají metody available(),
 * jestli nějaká data čekají, a když ne, na 20 ms usnou (Thread.sleep). Díky tomu je jde
 * kdykoli ukončit, třeba po příkazu /QUIT.
 */
public class TelnetClient {

    /** Pauza ve smyčce čekání na data (zadání: Thread.sleep(20)). */
    private static final int POLL_DELAY_MS = 20;

    /** Jak dlouho se maximálně čeká na navázání spojení. */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** Příkaz, kterým uživatel ukončí spojení i aplikaci. */
    private static final String QUIT_COMMAND = "/QUIT";

    private final String serverIp;
    private final int port;

    private Socket socket;

    /** Dokud je true, obě vlákna běží. volatile = změnu hned uvidí i druhé vlákno. */
    private volatile boolean running = true;

    public TelnetClient(String serverIp, int port) {
        this.serverIp = serverIp;
        this.port = port;
    }

    /**
     * Připojí se k serveru a běží, dokud se spojení neukončí.
     *
     * @return 0 když vše proběhlo v pořádku, 1 když se nepodařilo připojit
     */
    public int run() {
        socket = new Socket();

        try {
            socket.connect(new InetSocketAddress(serverIp, port), CONNECT_TIMEOUT_MS);

            // Odeslat zprávu hned, ne čekat na další data (vypnutí Nagleova algoritmu).
            socket.setTcpNoDelay(true);

            // Krátký timeout pro čtení: read() nikdy nezůstane viset (viz receiveLoop).
            socket.setSoTimeout(1);
        } catch (IOException e) {
            // Chyby jdou na System.err, aby na System.out zůstal jen text od serveru.
            System.err.println("Nepodařilo se připojit k " + serverIp + ":" + port + " (" + e.getMessage() + ")");
            closeSocket();
            return 1;
        }

        Thread receiver = new Thread(this::receiveLoop, "receiver");
        Thread sender = new Thread(this::sendLoop, "sender");
        receiver.start();
        sender.start();

        // Počkáme, až obě vlákna skončí. Pak se ukončí i celý program.
        try {
            receiver.join();
            sender.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
        }

        return 0;
    }

    /**
     * Vlákno 1: čte data ze serveru a vypisuje je na standardní výstup.
     */
    private void receiveLoop() {
        byte[] buffer = new byte[8192];

        try {
            InputStream in = socket.getInputStream();

            while (running) {
                int ready = in.available();

                if (ready > 0) {
                    // Něco čeká: read() se nezablokuje, protože víme, že data už jsou tady.
                    int count = in.read(buffer, 0, Math.min(ready, buffer.length));
                    if (count == -1) {
                        break; // server zavřel spojení
                    }
                    printToConsole(buffer, 0, count);
                } else {
                    // Nic nečeká, chvíli počkáme (aby smyčka nevytěžovala procesor).
                    Thread.sleep(POLL_DELAY_MS);

                    // available() neumí poznat, že server zavřel spojení (jen vrací 0).
                    // Proto zkusíme přečíst jeden bajt s krátkým timeoutem (viz setSoTimeout).
                    try {
                        int b = in.read();
                        if (b == -1) {
                            break; // server zavřel spojení
                        }
                        buffer[0] = (byte) b;
                        printToConsole(buffer, 0, 1);
                    } catch (SocketTimeoutException e) {
                        // Za chvilku nic nepřišlo. To je v pořádku, jedeme dál.
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Když jsme spojení zavřeli sami (/QUIT), je výjimka očekávaná a nehlásí se.
            if (running) {
                System.err.println("Chyba při čtení ze serveru: " + e.getMessage());
            }
        } finally {
            stop(); // když skončí příjem, nemá smysl pokračovat ani s odesíláním
        }
    }

    /**
     * Vlákno 2: čte klávesnici (System.in) a po každém ENTER pošle řádek na server.
     */
    private void sendLoop() {
        byte[] buffer = new byte[8192];

        // Sem se skládá rozepsaný řádek, dokud uživatel nezmáčkne ENTER.
        ByteArrayOutputStream line = new ByteArrayOutputStream();

        try {
            InputStream keyboard = System.in;
            OutputStream out = socket.getOutputStream();

            while (running) {
                int ready = keyboard.available();

                if (ready <= 0) {
                    Thread.sleep(POLL_DELAY_MS);
                    continue;
                }

                int count = keyboard.read(buffer, 0, Math.min(ready, buffer.length));
                if (count == -1) {
                    break; // vstup skončil, už nemá co odesílat (příjem může běžet dál)
                }

                for (int i = 0; i < count; i++) {
                    line.write(buffer[i]);

                    if (buffer[i] == '\n') { // uživatel zmáčkl ENTER
                        boolean keepRunning = handleLine(line.toByteArray(), out);
                        line.reset();

                        if (!keepRunning) {
                            stop(); // /QUIT: zavřít spojení, tím skončí i příjem
                            return;
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
        } catch (IOException e) {
            // Když jsme spojení zavřeli sami (/QUIT), je výjimka očekávaná a nehlásí se.
            if (running) {
                System.err.println("Chyba při odesílání na server: " + e.getMessage());
            }
            stop();
        }
    }

    /**
     * Zpracuje jeden hotový řádek (včetně konce řádku).
     *
     * @return false, když to byl příkaz /QUIT a má se skončit, jinak true
     */
    private boolean handleLine(byte[] lineBytes, OutputStream out) throws IOException {
        if (QUIT_COMMAND.equals(withoutLineEnding(lineBytes))) {
            return false; // /QUIT se na server neposílá, jen se ukončí spojení
        }

        // Řádek se posílá přesně tak, jak ho uživatel napsal (včetně konce řádku).
        out.write(lineBytes);
        out.flush();
        return true;
    }

    /** Převede řádek na text a odřízne z konce "\n" nebo "\r\n". Slouží jen k porovnání s /QUIT. */
    private static String withoutLineEnding(byte[] lineBytes) {
        String text = new String(lineBytes, StandardCharsets.UTF_8);

        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * Vypíše přijaté bajty na standardní výstup beze změny (žádné převody kódování ani konců řádků).
     */
    private static void printToConsole(byte[] data, int offset, int length) {
        System.out.write(data, offset, length);
        System.out.flush();
    }

    /** Ukončí běh: zastaví obě vlákna a zavře spojení. Je bezpečné volat opakovaně. */
    private void stop() {
        running = false;
        closeSocket();
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // Při zavírání už není co řešit.
        }
    }
}
