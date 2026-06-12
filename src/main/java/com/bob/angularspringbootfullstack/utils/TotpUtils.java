package com.bob.angularspringbootfullstack.utils;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * In-house implementation of RFC 6238 time-based one-time passwords (SRS FR-MFA-4),
 * plus the supporting pieces an enrollment flow needs: Base32 secret generation,
 * {@code otpauth://} provisioning URIs, QR rendering, and recovery-code helpers.
 *
 * <p>The TOTP algorithm itself is deliberately implemented with plain
 * {@code javax.crypto} rather than a third-party OTP library — the project's thesis is
 * an in-house CIAM core, and RFC 6238 is small enough to own: HMAC-SHA1 over a 30-second
 * time counter, dynamically truncated to 6 digits (RFC 4226 §5.3). SHA1 here is the
 * keyed-MAC usage (not collision-prone plain hashing) and is what Google Authenticator,
 * Authy, and 1Password expect by default; advertising another algorithm in the
 * provisioning URI breaks several authenticator apps silently.
 *
 * <p>Consumed by {@link com.bob.angularspringbootfullstack.service.serviceimpl.TotpServiceImpl},
 * which owns persistence and policy; nothing in this class touches the database.
 */
public final class TotpUtils {

    /** RFC 6238 default time step: a fresh code every 30 seconds. */
    private static final int TIME_STEP_SECONDS = 30;
    /** Standard 6-digit codes — what every mainstream authenticator app renders. */
    private static final int CODE_DIGITS = 6;
    /**
     * Accept the previous/next time window in addition to the current one (±30s),
     * absorbing user typing delay and modest clock drift between phone and server
     * without materially weakening the factor.
     */
    private static final int DRIFT_WINDOWS = 1;
    /** 160-bit secrets — the RFC 4226 recommended seed size and Base32-aligned (32 chars). */
    private static final int SECRET_BYTES = 20;
    /** RFC 4648 Base32 alphabet used for secrets and recovery codes (no 0/1/8/9 ambiguity). */
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TotpUtils() {
    }

    /**
     * Generates a new random 160-bit shared secret, Base32-encoded for storage and
     * for manual entry into an authenticator app.
     *
     * @return a 32-character Base32 secret
     */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * Verifies a user-submitted 6-digit code against the shared secret, accepting the
     * current 30-second window plus {@link #DRIFT_WINDOWS} on either side.
     *
     * <p>Comparison uses {@link MessageDigest#isEqual} so the check is constant-time
     * with respect to the candidate code's content.
     *
     * @param base32Secret the Base32 secret persisted at enrollment
     * @param code         the code the user typed (whitespace tolerated)
     * @return true when the code matches any accepted window
     */
    public static boolean verifyCode(String base32Secret, String code) {
        if (code == null) return false;
        String normalized = code.replaceAll("\\s", "");
        if (!normalized.matches("\\d{" + CODE_DIGITS + "}")) return false;
        long currentWindow = System.currentTimeMillis() / 1000 / TIME_STEP_SECONDS;
        byte[] candidate = normalized.getBytes(StandardCharsets.US_ASCII);
        for (long offset = -DRIFT_WINDOWS; offset <= DRIFT_WINDOWS; offset++) {
            byte[] expected = generateCode(base32Secret, currentWindow + offset).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes the RFC 6238 code for one specific time window: HMAC-SHA1 of the
     * big-endian counter, dynamically truncated per RFC 4226 §5.3, modulo 10^6,
     * left-padded with zeros.
     *
     * @param base32Secret the Base32 shared secret
     * @param timeWindow   the time counter (unix-seconds / 30)
     * @return the 6-digit code for that window
     */
    private static String generateCode(String base32Secret, long timeWindow) {
        try {
            byte[] counter = new byte[8];
            for (int i = 7; i >= 0; i--) {
                counter[i] = (byte) (timeWindow & 0xFF);
                timeWindow >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(base32Secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(counter);
            int dynamicOffset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[dynamicOffset] & 0x7F) << 24)
                    | ((hash[dynamicOffset + 1] & 0xFF) << 16)
                    | ((hash[dynamicOffset + 2] & 0xFF) << 8)
                    | (hash[dynamicOffset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", otp);
        } catch (Exception exception) {
            throw new ApiException("Unable to compute the verification code. Please try again.");
        }
    }

    /**
     * Builds the {@code otpauth://totp/...} provisioning URI that authenticator apps
     * consume (directly or via the QR code from {@link #qrCodeDataUri}). The label is
     * {@code issuer:account} per the de-facto Key Uri Format, and the issuer is repeated
     * as a query parameter because some apps only read one of the two locations.
     *
     * @param issuer  the application name shown in the authenticator (e.g. "SecureCapita")
     * @param account the user-facing account label (the user's email)
     * @param secret  the Base32 shared secret
     * @return the complete otpauth URI
     */
    public static String buildOtpAuthUri(String issuer, String account, String secret) {
        String encodedIssuer = URLEncoder.encode(issuer, StandardCharsets.UTF_8);
        String encodedAccount = URLEncoder.encode(account, StandardCharsets.UTF_8);
        return "otpauth://totp/" + encodedIssuer + ":" + encodedAccount
                + "?secret=" + secret
                + "&issuer=" + encodedIssuer
                + "&algorithm=SHA1&digits=" + CODE_DIGITS + "&period=" + TIME_STEP_SECONDS;
    }

    /**
     * Renders text (the otpauth URI) as a QR code PNG and returns it as a base64
     * {@code data:} URI the SPA can drop straight into an {@code <img src>}.
     *
     * <p>Rendering server-side is a deliberate security choice: the shared secret inside
     * the URI never travels to a third-party QR service and the frontend needs no QR
     * dependency — the image arrives in the same authenticated JSON response as the secret.
     *
     * @param content the text to encode (the otpauth URI)
     * @param size    the square image dimension in pixels
     * @return a {@code data:image/png;base64,...} URI
     */
    public static String qrCodeDataUri(String content, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", png);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
        } catch (Exception exception) {
            throw new ApiException("Unable to generate the enrollment QR code. Please try again.");
        }
    }

    /**
     * Generates one human-friendly recovery code in {@code XXXXX-XXXXX} format from the
     * Base32 alphabet (50 bits of entropy) — high enough that hashing with fast SHA-256
     * (see {@link #sha256Hex}) is appropriate, unlike low-entropy passwords which need BCrypt.
     *
     * @return a fresh single-use recovery code
     */
    public static String generateRecoveryCode() {
        StringBuilder code = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) code.append('-');
            code.append(BASE32_ALPHABET.charAt(SECURE_RANDOM.nextInt(BASE32_ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * SHA-256 digest as lowercase hex — the at-rest form of recovery codes, so a database
     * leak does not expose usable codes.
     *
     * @param value the plaintext to digest (normalized by the caller)
     * @return 64 hex characters
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new ApiException("Unable to process the recovery code. Please try again.");
        }
    }

    /**
     * RFC 4648 Base32 encoding without padding (authenticator apps reject padded secrets).
     */
    private static String base32Encode(byte[] data) {
        StringBuilder encoded = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsInBuffer = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsInBuffer += 8;
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5;
                encoded.append(BASE32_ALPHABET.charAt((buffer >> bitsInBuffer) & 0x1F));
            }
        }
        if (bitsInBuffer > 0) {
            encoded.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsInBuffer)) & 0x1F));
        }
        return encoded.toString();
    }

    /**
     * RFC 4648 Base32 decoding (case-insensitive, padding tolerated) back to the raw
     * HMAC key bytes.
     */
    private static byte[] base32Decode(String base32) {
        String normalized = base32.trim().replace("=", "").toUpperCase();
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(normalized.length() * 5 / 8);
        int buffer = 0;
        int bitsInBuffer = 0;
        for (char c : normalized.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) throw new ApiException("Invalid authenticator secret.");
            buffer = (buffer << 5) | value;
            bitsInBuffer += 5;
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8;
                decoded.write((buffer >> bitsInBuffer) & 0xFF);
            }
        }
        return decoded.toByteArray();
    }
}
