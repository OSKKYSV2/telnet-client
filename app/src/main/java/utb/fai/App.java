package utb.fai;

public class App {

    private static final String USAGE = "Použití: java -jar app.jar <IP adresa> <port>";

    public static void main(String[] args) {
        // Očekáváme přesně dva parametry: IP adresu a port.
        if (args.length != 2) {
            fail("Chybný počet parametrů (očekávám 2, dostal jsem " + args.length + ").");
        }

        String ip = args[0];
        if (ip.isBlank()) {
            fail("IP adresa nesmí být prázdná.");
        }

        int port = parsePort(args[1]);

        TelnetClient telnetClient = new TelnetClient(ip, port);
        int exitCode = telnetClient.run(); // běží, dokud se spojení neukončí

        System.exit(exitCode);
    }

    /** Převede text na číslo portu a zkontroluje, že je v rozsahu 1 až 65535. */
    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);

            if (port < 1 || port > 65535) {
                fail("Port musí být číslo od 1 do 65535, ne " + port + ".");
            }
            return port;
        } catch (NumberFormatException e) {
            fail("Port musí být celé číslo, ne \"" + text + "\".");
            return -1; // sem se nikdy nedostaneme, fail() ukončí program
        }
    }

    /** Vypíše chybu a návod na System.err (System.out zůstává jen pro text od serveru) a skončí s kódem 1. */
    private static void fail(String message) {
        System.err.println(message);
        System.err.println(USAGE);
        System.exit(1);
    }
}
