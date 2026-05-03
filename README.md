# 🚀 Angular Spring Boot Full Stack Application

A production-ready full-stack application combining **Angular** (frontend) and **Spring Boot** (backend) with
comprehensive security features including JWT authentication, refresh tokens, 2FA, and role-based access control.

---

## 📋 Requirements

- **Java**: 21 or higher
- **Node.js**: 18 or higher
- **Angular CLI**: 20 or higher
- **PostgreSQL**: 12 or higher
- **Maven**: 3.8+

---

## 🛠️ Project Features

### Backend (Spring Boot)

- ✅ JWT Token Authentication (Access + Refresh tokens)
- ✅ Two-Factor Authentication (2FA)
- ✅ Role-Based Access Control (RBAC)
- ✅ Password Reset with Verification Links
- ✅ Account Verification
- ✅ BCrypt Password Hashing
- ✅ Custom Security Filters & Exception Handling
- ✅ Comprehensive Logging & Auditing

### Frontend (Angular)

*(Set up via FRONTEND_DOCUMENTATION_SETUP.md)*

---

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│         SPRING SECURITY FILTER CHAIN                 │
├─────────────────────────────────────────────────────┤
│ CustomAuthFilter (JWT validation & authentication)  │
│         ↓                                            │
│ SecurityFilterChain (authorization rules)           │  
│         ↓                                            │
│ Controller Layer (REST endpoints)                   │
│         ↓                                            │
│ Service Layer (business logic)                      │
│         ↓                                            │
│ Repository Layer (database operations)              │
└─────────────────────────────────────────────────────┘
```

---

## 🚀 Quick Start

### Step 1: Backend Setup

#### 1a. Clone and navigate to project

```powershell
cd B:\Documents\Coding\angularSpringBootFullStack
```

#### 1b. Configure PostgreSQL Database

Create a new PostgreSQL database:

```sql
CREATE DATABASE angular_spring_boot;
```

#### 1c. Update database configuration

Edit `src/main/resources/application.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/angular_spring_boot
    username: postgres  # Your PostgreSQL username
    password: password  # Your PostgreSQL password
  jpa:
    hibernate:
      ddl-auto: update  # Creates tables automatically
```

#### 1d. Build the backend

```powershell
.\mvnw.cmd clean package
```

Or skip tests for faster build:

```powershell
.\mvnw.cmd clean package -DskipTests
```

#### 1e. Run the backend

```powershell
.\mvnw.cmd spring-boot:run
```

The backend will start at: **http://localhost:8080**

✅ Check health endpoint:

```powershell
curl -X GET http://localhost:8080/actuator/health
# Response: {"status":"UP"}
```

---

### Step 2: Frontend Setup

Follow **FRONTEND_DOCUMENTATION_SETUP.md** for Angular setup.

---

## 📚 API Usage Guide

### 1️⃣ User Registration

**Endpoint:** `POST /user/register`

**Request:**

```bash
curl -X POST http://localhost:8080/user/register \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "John",
    "lastName": "Doe",
    "email": "john@example.com",
    "password": "P@ssw0rd123"
  }'
```

**Response (201 Created):**

```json
{
  "timeStamp": "12:30:45.123456",
  "data": {
    "user": {
      "id": 1,
      "email": "john@example.com",
      "firstName": "John",
      "lastName": "Doe",
      "enabled": true,
      "using2FA": false
    }
  },
  "message": "User created successfully!",
  "status": "201 CREATED",
  "statusCode": 201
}
```

**Server Log:**

```
INFO: Account verification url http://localhost:8080/user/verify/account/550e8400-e29b-41d4-a716-446655440000 sent to user with email: john@example.com
```

---

### 2️⃣ User Login (Get Tokens)

**Endpoint:** `POST /user/login`

**Request:**

```bash
curl -X POST http://localhost:8080/user/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john@example.com",
    "password": "P@ssw0rd123"
  }'
```

**Response (200 OK):**

```json
{
  "timeStamp": "12:31:00.123456",
  "data": {
    "user": {
      "id": 1,
      "email": "john@example.com",
      "firstName": "John",
      "lastName": "Doe"
    },
    "access_token": "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9...",
    "refresh_token": "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9..."
  },
  "message": "Login successful!",
  "status": "200 OK",
  "statusCode": 200
}
```

**Token Structure:**

- **Access Token**: Valid 30 minutes, includes authorities (permissions)
- **Refresh Token**: Valid 5 days, no authorities, refresh access tokens only

---

### 3️⃣ Access Protected Endpoint (with Access Token)

**Endpoint:** `GET /user/profile`

**Request:**

```bash
curl -X GET http://localhost:8080/user/profile \
  -H "Authorization: Bearer <access_token>"
```

**Response (200 OK):**

```json
{
  "timeStamp": "12:31:30.123456",
  "data": {
    "user": {
      "id": 1,
      "email": "john@example.com",
      "firstName": "John",
      "lastName": "Doe"
    }
  },
  "message": "We have fetched your profile for you!",
  "status": "200 OK",
  "statusCode": 200
}
```

**If access token is expired → 401 Unauthorized**

---

### 4️⃣ Refresh Access Token

**Endpoint:** `GET /user/refresh/token`

**Request:**

```bash
curl -X GET http://localhost:8080/user/refresh/token \
  -H "Authorization: Bearer <refresh_token>"
```

**Response (200 OK):**

```json
{
  "timeStamp": "12:32:00.123456",
  "data": {
    "user": {
      ...
    },
    "access_token": "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9...",
    "refresh_token": "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9..."
  },
  "message": "New refresh token sent successfully!",
  "status": "200 OK",
  "statusCode": 200
}
```

**Server Log:**

```
INFO: Getting role to user with ID 1
```

---

### 5️⃣ Password Reset Request

**Endpoint:** `GET /user/resetpassword/{email}`

**Request:**

```bash
curl -X GET http://localhost:8080/user/resetpassword/john@example.com
```

**Response (200 OK):**

```json
{
  "message": "Email sent to reset password. Please check your inbox.",
  "status": "200 OK",
  "statusCode": 200
}
```

**Server Log:**

```
INFO: Password reset verification url http://localhost:8080/user/verify/password/550e8400-e29b-41d4-a716-446655440000 sent to user with email: john@example.com
```

---

### 6️⃣ Verify Password Reset Link

**Endpoint:** `GET /user/verify/password/{key}`

**Request:**

```bash
curl -X GET "http://localhost:8080/user/verify/password/550e8400-e29b-41d4-a716-446655440000"
```

**Response (200 OK):**

```json
{
  "data": {
    "user": {
      "id": 1,
      "email": "john@example.com",
      "firstName": "John",
      "lastName": "Doe"
    }
  },
  "message": "Please enter your new password",
  "status": "200 OK",
  "statusCode": 200
}
```

---

### 7️⃣ Set New Password

**Endpoint:** `POST /user/resetpassword/{key}/{newPassword}/{confirmPassword}`

**Request:**

```bash
curl -X POST "http://localhost:8080/user/resetpassword/550e8400-e29b-41d4-a716-446655440000/NewPassword123/NewPassword123"
```

**Response (200 OK):**

```json
{
  "message": "Password reset successful! You can now log in with your new password.",
  "status": "200 OK",
  "statusCode": 200
}
```

**Server Log:**

```
INFO: Password successfully reset for user with email: john@example.com
```

---

### 8️⃣ Account Verification

**Endpoint:** `GET /user/verify/account/{key}`

**Request:**

```bash
curl -X GET "http://localhost:8080/user/verify/account/550e8400-e29b-41d4-a716-446655440000"
```

**Response (200 OK):**

```json
{
  "message": "Account verified successfully! You can now log in.",
  "status": "200 OK",
  "statusCode": 200
}
```

**Server Log:**

```
INFO: Account successfully verified for user with email: john@example.com
```

---

## 🔐 Authentication & Authorization

### How JWT Works

1. **Login**: User sends email + password → Server verifies → Generates access + refresh tokens
2. **Access Protected Resource**: Client sends access token in Authorization header
3. **Filter Checks**: CustomAuthFilter validates token signature + expiration
4. **Authorization**: SecurityConfig checks if user has required authorities
5. **Token Expired**: Client uses refresh token to get new access token

### Token Claims (Payload)

**Access Token Example:**

```json
{
  "sub": "john@example.com",
  "authorities": [
    "READ:USER",
    "UPDATE:USER",
    "DELETE:USER"
  ],
  "iss": "BOBBYLON_LLC",
  "aud": "BOBS_MANAGEMENT",
  "exp": 1715000000,
  "iat": 1714995600
}
```

**Refresh Token Example:**

```json
{
  "sub": "john@example.com",
  "iss": "BOBBYLON_LLC",
  "aud": "BOBS_MANAGEMENT",
  "exp": 1715259600,
  "iat": 1714995600
}
```

Note: Refresh token intentionally lacks "authorities" to prevent misuse.

---

## 📊 Error Handling

### Common Error Responses

#### 401 Unauthorized

```json
{
  "reason": "Token has expired",
  "status": "401 UNAUTHORIZED",
  "statusCode": 401
}
```

#### 403 Forbidden

```json
{
  "reason": "Access Denied: You do not have permission to access this resource",
  "status": "403 FORBIDDEN",
  "statusCode": 403
}
```

#### 400 Bad Request

```json
{
  "reason": "Could not decode the token. The input is not a valid Base64-encoded JWT.",
  "status": "400 BAD_REQUEST",
  "statusCode": 400
}
```

#### 500 Internal Server Error

```json
{
  "reason": "An error has occurred, please try again",
  "status": "500 INTERNAL_SERVER_ERROR",
  "statusCode": 500
}
```

---

## 📝 Logging & Auditing

The application logs important security events:

```
INFO: Creating new user with email: john@example.com
INFO: Account verification url http://localhost:8080/... sent to user with email: john@example.com
INFO: Getting role to user with ID 1
INFO: Building UserPrincipal for user with email: john@example.com and id: 1
INFO: Password reset verification url http://localhost:8080/... sent to user with email: john@example.com
INFO: Password successfully reset for user with email: john@example.com
INFO: Account successfully verified for user with email: john@example.com
```

---

## 📖 Documentation

For detailed technical documentation, see:

- **SPRING_SECURITY_DETAILED_GUIDE.md** - Complete Spring Security explanation
- **DOCUMENTATION_SUMMARY.md** - Overview of all documented files
- **DOCUMENTATION_INDEX.md** - Navigation guide for architecture
- **REFACTORING_COMPLETE_SUMMARY.md** - Summary of improvements
- **FRONTEND_DOCUMENTATION_SETUP.md** - Angular setup guide

---

## 🛠️ Development

### Build Project

```powershell
.\mvnw.cmd clean package
```

### Run Tests

```powershell
.\mvnw.cmd test
```

### Generate Javadoc

```powershell
.\mvnw.cmd javadoc:javadoc
```

---

## 🔧 Troubleshooting

### Port 8080 Already in Use

```powershell
# Change port in application.yaml
server:
  port: 8081
```

# Requirements:

Java 21 or higher
Node.js 18 or higher
Angular CLI 20 or higher
PostgreSQL

# Steps:

1. Set up the Spring Boot backend:
    - Create a new Spring Boot project using Spring Initializr (https://start.spring.io/).
    - Add dependencies for Spring Web, Spring Data JPA, and PostgreSQL Driver.
    - Configure the application.properties file to connect to your PostgreSQL database.
    - Create entities, repositories, and controllers for your backend API.
    - Run the Spring Boot application to ensure it's working correctly.
2. Set up the Angular frontend:
    - Create a new Angular project using Angular CLI (https://angular.io/cli).
    - Generate components, services, and models for your frontend application.
    - Use HttpClient to make API calls to your Spring Boot backend.
    - Implement routing and UI components to display data from the backend.
    - Run the Angular application to ensure it's working correctly.
3. Creating the database:
    - Use PostgreSQL to create a new database for your application.
    - Create tables and insert sample data as needed for your application.

