import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

public class GenerateKeys {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of("keys");
        Files.createDirectories(directory);
        Path privateFile = directory.resolve("private.pem");
        Path publicFile = directory.resolve("public.pem");
        if (Files.exists(privateFile) || Files.exists(publicFile)) {
            throw new IllegalStateException("Keys already exist; no files were changed");
        }
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        Files.writeString(privateFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
        Files.writeString(publicFile, pem("PUBLIC KEY", pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);
        System.out.println("Created keys/private.pem and keys/public.pem");
    }

    private static String pem(String type, byte[] data) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(data);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }
}
