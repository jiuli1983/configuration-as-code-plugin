package io.jenkins.plugins.casc.vault;

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.utility.TestEnvironment;
import org.testcontainers.vault.VaultContainer;

import java.io.IOException;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("WeakerAccess")
class VaultTestUtil {

    private final static Logger LOGGER = Logger.getLogger(VaultTestUtil.class.getName());

    private static final String VAULT_DOCKER_IMAGE = "vault:1.0.3";
    public static final String VAULT_ROOT_TOKEN = "root-token";
    public static final String VAULT_USER = "admin";
    public static final String VAULT_PW = "admin";
    public static final String VAULT_PATH_KV1_1 = "kv-v1/admin";
    public static final String VAULT_PATH_KV1_2 = "kv-v1/dev";
    public static final String VAULT_PATH_KV2_1 = "kv-v2/admin";
    public static final String VAULT_PATH_KV2_2 = "kv-v2/dev";
    public static final String VAULT_PATH_KV2_3 = "kv-v2/qa";
    public static final String VAULT_PATH_KV2_AUTH_TEST = "kv-v2/auth-test";
    public static final String VAULT_APPROLE_FILE = "JCasC_temp_approle_secret.prop";
    private static boolean configured = false;

    public static void runCommand(VaultContainer container, final String... command)
        throws IOException, InterruptedException {
        LOGGER.log(Level.FINE, String.join(" ", command));
        container.execInContainer(command);
    }

    public static boolean hasDockerDaemon() {
        try {
            return TestEnvironment.dockerApiAtLeast("1.10");
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static VaultContainer createVaultContainer() {
        if (!hasDockerDaemon()) return null;
        return new VaultContainer<>(VaultTestUtil.VAULT_DOCKER_IMAGE)
                .withVaultToken(VaultTestUtil.VAULT_ROOT_TOKEN)
                .withCopyFileToContainer(MountableFile.forHostPath(
                        VaultTestUtil.class.getResource("vaultTest_adminPolicy.hcl").getPath()),
                        "/admin.hcl")
                .withVaultPort(8200)
                .waitingFor(Wait.forHttp("/v1/sys/seal-status").forStatusCode(200));
    }

    public static void configureVaultContainer(VaultContainer container) {
        if (configured) return;
        try {
            // Create Secret Backends
            runCommand(container, "vault", "secrets", "enable", "-path=kv-v2",
                "-version=2", "kv");
            runCommand(container, "vault", "secrets", "enable", "-path=kv-v1",
                "-version=1", "kv");

            // Create user/password credential
            runCommand(container, "vault", "auth", "enable", "userpass");
            runCommand(container, "vault", "write", "auth/userpass/users/" + VAULT_USER,
                "password=" + VAULT_PW, "policies=admin");

            // Create policies
            runCommand(container, "vault", "policy", "write", "admin", "/admin.hcl");

            // Create AppRole
            runCommand(container, "vault", "auth", "enable", "approle");
            runCommand(container, "vault", "write", "auth/approle/role/admin",
                "secret_id_ttl=10m", "token_num_uses=0", "token_ttl=4s", "token_max_ttl=4s",
                "secret_id_num_uses=1000", "policies=admin");

            // Retrieve AppRole credentials
            VaultConfig config = new VaultConfig().address("http://localhost:8200")
                .token(VAULT_ROOT_TOKEN).engineVersion(1).build();
            Vault vaultClient = new Vault(config);
            final String roleID = vaultClient.logical().read("auth/approle/role/admin/role-id")
                .getData().get("role_id");
            final String secretID = vaultClient.logical().write("auth/approle/role/admin/secret-id",
                new HashMap<>()).getData().get("secret_id");

            Properties properties = new Properties();
            properties.put("CASC_VAULT_APPROLE", roleID);
            properties.put("CASC_VAULT_APPROLE_SECRET", secretID);

            Path filePath = Paths.get(System.getProperty("java.io.tmpdir"), VAULT_APPROLE_FILE);
            File file = filePath.toFile();
            FileOutputStream fos = new FileOutputStream(file);
            properties.store(fos, null);

            // add secrets for v1 and v2
            runCommand(container, "vault", "kv", "put", VAULT_PATH_KV1_1, "key1=123",
                "key2=456");
            runCommand(container, "vault", "kv", "put", VAULT_PATH_KV1_2, "key3=789");
            runCommand(container, "vault", "kv", "put", VAULT_PATH_KV2_1, "key1=123",
                "key2=456");
            runCommand(container, "vault", "kv", "put", VAULT_PATH_KV2_2, "key3=789");
            runCommand(container, "vault", "kv", "put", VAULT_PATH_KV2_3, "key2=321");
            runCommand(container, "vault", "kv", "put", VAULT_PATH_KV2_AUTH_TEST,
                "key1=auth-test");
            LOGGER.log(Level.INFO, "Vault is configured");
            configured = true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage());
        }
    }
}
