package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.form.LoginForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Map;

import static java.time.LocalTime.now;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.OK;

@RestController
@RequestMapping(path = "/user")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    // reminder: this is going to be doing our authentication, the first step AFTER the filter in our Spring Security Flow. We must also define the authenticationMangaer Bean to prevent this from failing
    private final AuthenticationManager authenticationManager;

    @PostMapping("/register")
    public ResponseEntity<HttpResponse> saveUser(@RequestBody @Valid User user) {
        UserDTO userDTO = userService.createUser(user);
        return ResponseEntity.created(getUri()).body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(Map.of("user", userDTO))
                        .message("User created successfully!")
                        .status(CREATED)
                        .statusCode(CREATED.value())
                        .build());
    }

    // we have to inject our authenticationManager here, since it will be called after our securityFilter. This would be our first step after the filters.
    private URI getUri() {
        // if you go to this endpoint, we have to look at the ID of the specific resource/controller and give it the id, in this case userId
        return URI.create(ServletUriComponentsBuilder.fromCurrentContextPath().path("/user/get/<userId>").toUriString());
    }

    @PostMapping("/login")
    public ResponseEntity<HttpResponse> login(@RequestBody @Valid LoginForm loginForm) {
        /*  To explain what is happening here, we can take a look at the AuthenticationManager interface and its .authenticate() method.
            Within it, it uses a parameter of type Authentication which is another interface, and When we click on the button next to the Authentication interface, we see an implementation UsernamePasswordAuthenticationToken.
            It has a constructor that takes two @Nullable Objects as parameters (principle, and password), so we will use email and password in our use-case/scenario.
            This Object will get sent down the flow of the authentication process, which again is going from Filter > ProviderManager (AuthenticationManager) > AuthenticationProvider, etc.
            If we wanted to, we could even create our own implementation of the Authentication interface (highly recommended)
        */
        // don't forget we must call the constructor along with the parameters we want to use.
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(loginForm.getEmail(), loginForm.getPassword()));
        UserDTO userDTO = userService.getUserByEmail(loginForm.getEmail());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(Map.of("user", userDTO))
                        .message("Login successful!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

}
