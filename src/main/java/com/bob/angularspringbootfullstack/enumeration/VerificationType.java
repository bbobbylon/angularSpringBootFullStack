package com.bob.angularspringbootfullstack.enumeration;

public enum VerificationType {
    // we will have two types of verification URLs - one for account verification and one for password reset
    ACCOUNT("ACCOUNT"),
    PASSWORD("PASSWORD");

    // method to define the strings, because we will need a way to get the type somehow
    private final String type;

    VerificationType(String type) {
        this.type = type;
    }

    // this returns the type of verification as a string from the enum (above)
    public String getType() {
        return this.type.toLowerCase();
    }

}
