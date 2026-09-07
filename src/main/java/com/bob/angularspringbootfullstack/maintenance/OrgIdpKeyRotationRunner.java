package com.bob.angularspringbootfullstack.maintenance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-shot CLI entry point for {@link OrgIdpKeyRotationService#rotate} — see that class and
 * {@code aws/RUNBOOK.md} → "Rotating the org SSO encryption key" for the full procedure.
 * <p>
 * Gated behind the {@code key-rotation} Spring profile so this bean is never instantiated, and
 * the rotation can never run accidentally, during an ordinary {@code dev}/{@code prod}/{@code qa}/
 * {@code stage} boot. The operator runs it deliberately: point {@code ORG_IDP_SECRET_ENCRYPTION_KEY}
 * at the <em>old</em> key (the app's normal config), set {@code ORG_IDP_KEY_ROTATION_NEW_KEY} to the
 * freshly generated one, and start the jar with {@code --spring.profiles.active=key-rotation}.
 * <p>
 * Exits the process itself via {@link SpringApplication#exit} rather than letting the boot
 * continue into a listening web server — a key-rotation run is a script, not a long-lived
 * instance, and leaving it running would falsely look like a healthy deployment to anyone
 * watching the process list.
 */
@Slf4j
@Component
@Profile("key-rotation")
@RequiredArgsConstructor
public class OrgIdpKeyRotationRunner implements CommandLineRunner {

    private final OrgIdpKeyRotationService rotationService;
    private final ConfigurableApplicationContext context;

    @Value("${org.idp.secret-encryption-key:}")
    private String currentKey;

    @Value("${org.idp.key-rotation.new-key:}")
    private String newKey;

    @Override
    public void run(String... args) {
        System.exit(SpringApplication.exit(context, this::rotateAndReportExitCode));
    }

    private int rotateAndReportExitCode() {
        try {
            int rotated = rotationService.rotate(currentKey, newKey);
            log.info("[OrgIdpKeyRotationRunner] Rotated {} organization IdP secret(s) to the new key. "
                    + "Redeploy with ORG_IDP_SECRET_ENCRYPTION_KEY set to the new key before removing the old one.",
                    rotated);
            return 0;
        } catch (Exception e) {
            log.error("[OrgIdpKeyRotationRunner] Rotation aborted, no rows were changed: {}", e.getMessage());
            return 1;
        }
    }
}
