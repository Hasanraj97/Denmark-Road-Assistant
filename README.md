# Denmark Road Assistant

This project is a full-stack Denmark-focused road-advisory demo with:

- Angular frontend
- Spring Boot backend
- FastAPI ML service
- Leaflet + OpenStreetMap map UI
- synthetic ML-based travel advisory predictions
- JWT authentication and profile management

Important:
The ML predictions remain synthetic and illustrative. Authentication is handled only by Spring Boot, not by the FastAPI ML service.

## Project structure

```text
E:\sw8-maps
|-- frontend/
|-- backend/
|-- ml-service/
|-- .env.example
`-- README.md
```

## Version 2 features

- signup and login with JWT bearer token
- BCrypt password hashing
- protected profile page and `/api/users/me` endpoints
- user profile update flow
- Angular auth interceptor and route guard
- browser + Capacitor Android environment support
- existing route analysis, map, weather widget, and ML advisory remain in place

## Backend auth endpoints

- `POST /api/auth/signup`
- `POST /api/auth/login`
- `GET /api/users/me`
- `PUT /api/users/me`

## Database

The backend now uses Spring Data JPA.

Default local setup:
- file-based H2 database for easy local development

MySQL example:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/road_assistant
spring.datasource.username=mysql
spring.datasource.password=YOUR_PASSWORD
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.jpa.hibernate.ddl-auto=update
```

## Run locally

### 1. Start the ML service

```powershell
cd E:\sw8-maps\ml-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

### 2. Start the Spring Boot backend

```powershell
cd E:\sw8-maps\backend
.\mvnw.cmd spring-boot:run
```

### 3. Start the Angular web app

```powershell
cd E:\sw8-maps\frontend
npm install
npm start
```

Then open:

- `http://localhost:4200`

## Android / Capacitor

The backend stays on your PC.

- browser build uses `http://localhost:8080`
- Android emulator tries `http://10.0.2.2:8080`
- real phone can fall back to `http://192.168.1.103:8080`

Build and sync Android:

```powershell
cd E:\sw8-maps\frontend
npm run build:android
npm run cap:sync
npm run cap:open
```

## Authentication flow

1. User signs up or logs in through Angular
2. Spring Boot validates credentials
3. Spring Boot returns a JWT token and user payload
4. Angular stores the JWT in `localStorage`
5. Angular interceptor attaches `Authorization: Bearer <token>`
6. Protected profile endpoints read the authenticated user from the JWT

## Notes

- The FastAPI ML service is not responsible for authentication.
- Route analysis endpoints remain public in this version so the original demo flow is not broken.
- Profile endpoints are protected with Spring Security and JWT.
