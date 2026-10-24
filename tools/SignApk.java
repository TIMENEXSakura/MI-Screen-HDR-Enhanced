import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

public final class SignApk {
    public static void main(String[] arguments) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        char[] password = Files.readString(Path.of(arguments[1])).trim().toCharArray();
        try (var input = Files.newInputStream(Path.of(arguments[0]))) {
            store.load(input, password);
        }
        var config = new ApkSigner.SignerConfig.Builder("hdr-screenshot-local",
                (PrivateKey) store.getKey("hdr-screenshot-local", password),
                List.of((X509Certificate) store.getCertificate("hdr-screenshot-local"))).build();
        new ApkSigner.Builder(List.of(config))
                .setInputApk(new File(arguments[2]))
                .setOutputApk(new File(arguments[3]))
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setOtherSignersSignaturesPreserved(false)
                .setLibraryPageAlignmentBytes(16384)
                .build().sign();
        var result = new ApkVerifier.Builder(new File(arguments[3])).build().verify();
        System.out.println("Verified=" + result.isVerified()
                + " v1=" + result.isVerifiedUsingV1Scheme()
                + " v2=" + result.isVerifiedUsingV2Scheme()
                + " v3=" + result.isVerifiedUsingV3Scheme());
        System.out.println("Warnings=" + result.getWarnings());
        if (!result.isVerified()) throw new IllegalStateException(result.getAllErrors().toString());
    }
}
