package com.bob.angularspringbootfullstack.enumeration;

/**
 * VerificationType defines the types of verification URLs that can be generated.
 *
 * Different verification flows require different types of URLs:
 * - ACCOUNT: For new user account verification/activation
 * - PASSWORD: For password reset verification
 *
 * Each type is associated with a lowercase string that is used in verification URLs.
 * For example, an ACCOUNT verification URL might look like:
 *   http://example.com/user/verifyaccount/{UUID}
 */
public enum VerificationType {
    /** Account verification type (for new account activation) */
    ACCOUNT("ACCOUNT"),
    /** Password reset verification type */
    PASSWORD("PASSWORD");

    /** The string representation of this verification type */
    private final String type;

    /**
     * Constructs a VerificationType with the given type string.
     *
     * @param type the string representation of this verification type
     */
    VerificationType(String type) {
        this.type = type;
    }

    /**
     * Returns the verification type as a lowercase string.
     * Used in generating verification URLs and for database queries.
     *
     * @return the lowercase string representation of this verification type
     */
    public String getType() {
        return this.type.toLowerCase();
    }

}
