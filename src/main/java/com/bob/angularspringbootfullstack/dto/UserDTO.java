package com.bob.angularspringbootfullstack.dto;

import lombok.Data;

import java.time.LocalDateTime;

// this is going to be used to MIRROR the user, sending only the variables we want to send. Here, we will send everything except for the password
@Data
public class UserDTO {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    // private String password; we are removing this because we don't want to send this over to the front end.
    private String imageUrl;
    private String address;
    private String phoneNumber;
    private String bio;
    private String title;
    private boolean enabled;
    private boolean isNotLocked;
    private boolean isUsing2FA;
    private LocalDateTime createdAt;
}
