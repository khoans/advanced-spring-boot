package com.oms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.dto.AuthRequest;
import com.oms.dto.RegisterRequest;
import com.oms.entity.User;
import com.oms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("AuthController Integration Tests")
class AuthControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Register should create new user and return tokens")
    void register_shouldCreateUserAndReturnTokens() throws Exception {
        RegisterRequest request = new RegisterRequest("newuser", "new@example.com", "password123");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists())
            .andExpect(jsonPath("$.username").value("newuser"))
            .andExpect(jsonPath("$.email").value("new@example.com"))
            .andExpect(jsonPath("$.role").value("ROLE_CUSTOMER"));
    }

    @Test
    @DisplayName("Register with existing username should return 400")
    void register_withExistingUsername_shouldReturn400() throws Exception {
        User existingUser = User.builder()
            .username("existing")
            .email("existing@example.com")
            .password(passwordEncoder.encode("password"))
            .role(User.Role.ROLE_CUSTOMER)
            .build();
        userRepository.save(existingUser);

        RegisterRequest request = new RegisterRequest("existing", "new@example.com", "password123");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Register with existing email should return 400")
    void register_withExistingEmail_shouldReturn400() throws Exception {
        User existingUser = User.builder()
            .username("unique")
            .email("taken@example.com")
            .password(passwordEncoder.encode("password"))
            .role(User.Role.ROLE_CUSTOMER)
            .build();
        userRepository.save(existingUser);

        RegisterRequest request = new RegisterRequest("newuser", "taken@example.com", "password123");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Login with valid credentials should return tokens")
    void login_withValidCredentials_shouldReturnTokens() throws Exception {
        User user = User.builder()
            .username("loginuser")
            .email("login@example.com")
            .password(passwordEncoder.encode("password123"))
            .role(User.Role.ROLE_CUSTOMER)
            .build();
        userRepository.save(user);

        AuthRequest request = new AuthRequest("loginuser", "password123");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists())
            .andExpect(jsonPath("$.username").value("loginuser"));
    }

    @Test
    @DisplayName("Login with invalid credentials should return 400")
    void login_withInvalidCredentials_shouldReturn400() throws Exception {
        AuthRequest request = new AuthRequest("nonexistent", "wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Refresh token should return new tokens")
    void refreshToken_withValidToken_shouldReturnNewTokens() throws Exception {
        User user = User.builder()
            .username("refreshuser")
            .email("refresh@example.com")
            .password(passwordEncoder.encode("password123"))
            .role(User.Role.ROLE_CUSTOMER)
            .build();
        userRepository.save(user);

        AuthRequest loginRequest = new AuthRequest("refreshuser", "password123");
        String loginResponse = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String refreshToken = objectMapper.readTree(loginResponse).get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/refresh")
                .header("Authorization", "Bearer " + refreshToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists());
    }
}
