# API Requirements

This document tracks the endpoints used by the Android app and their current status on the backend.

## Base URL
- Emulator: `http://10.0.2.2:<port>/api/`
- Real Device: `http://<laptop-ip>:<port>/api/`

## Auth (`/api/auth`)

### POST /register
- **Description**: Register a new student account.
- **Request Body**:
  ```json
  {
    "email": "string",
    "password": "string",
    "role": "student"
  }
  ```
- **Response (201)**:
  ```json
  {
    "user": { "id": "string", "email": "string", "role": "student" },
    "token": "string"
  }
  ```
- **Auth Required**: No
- **Status**: WORKING

### POST /login
- **Description**: Authenticate an existing user.
- **Request Body**:
  ```json
  {
    "email": "string",
    "password": "string"
  }
  ```
- **Response (200)**:
  ```json
  {
    "user": { "id": "string", "email": "string", "role": "student" },
    "token": "string"
  }
  ```
- **Auth Required**: No
- **Status**: WORKING

---

## Assignments (`/api/assignments`)

### GET /
- **Description**: Get all assignments for the authenticated student.
- **Response (200)**:
  ```json
  [
    {
      "id": "string",
      "title": "string",
      "description": "string",
      "questions": [
        { "id": "string", "text": "string" }
      ]
    }
  ]
  ```
- **Auth Required**: Yes (Bearer Token)
- **Status**: WORKING

### GET /:id
- **Description**: Get details for a specific assignment.
- **Response (200)**:
  ```json
  {
    "id": "string",
    "title": "string",
    "description": "string",
    "questions": [
      { "id": "string", "text": "string" }
    ]
  }
  ```
- **Auth Required**: Yes (Bearer Token)
- **Status**: WORKING

---

## Submissions (`/api/submissions`)

### GET /me
- **Description**: Get all submissions for the authenticated student.
- **Response (200)**:
  ```json
  [
    {
      "id": "string",
      "assignment_id": "string",
      "status": "pending | graded",
      "submitted_at": "ISO-8601 string"
    }
  ]
  ```
- **Auth Required**: Yes (Bearer Token)
- **Status**: WORKING

### POST /
- **Description**: Submit a worksheet photo for an assignment.
- **Request Body**: Multipart form data
  - `assignment_id`: string
  - `worksheet_photo`: file (image)
- **Response (201)**:
  ```json
  {
    "id": "string",
    "status": "pending"
  }
  ```
- **Auth Required**: Yes (Bearer Token)
- **Status**: NOT YET BUILT — needed by Submit flow.
- **Note**: The current server expects `{ assignment_id, answers: [...] }`. This app will send a photo instead.

---

## Grades (`/api/grades`)

### GET /me
- **Description**: Get grades and feedback for the authenticated student.
- **Response (200)**:
  ```json
  [
    {
      "id": "string",
      "submission_id": "string",
      "assignment_id": "string",
      "grade": "string",
      "feedback": "string"
    }
  ]
  ```
- **Auth Required**: Yes (Bearer Token)
- **Status**: WORKING (but unused yet)
