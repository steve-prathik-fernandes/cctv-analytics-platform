# Technology Decisions

Status: Week 2 deliverable. Version 0.1, 2026-10-06.
Related: charter Section 3.7 (options analysis), [architecture](03-architecture.md).

Each decision lists the options considered, what was chosen, and what it costs. Where this document differs from the charter, the difference is called out.

## Summary

| ID | Area | Decision |
|---|---|---|
| D1 | Detection model | Pretrained YOLOv8n (COCO), no custom training |
| D2 | Tracking | Ultralytics built-in ByteTrack, BoT-SORT as fallback |
| D3 | Processing mode | Batch processing of uploaded recordings |
| D4 | Backend to detection service | HTTP job submission and HTTP callback, no message broker |
| D5 | Where event rules run | In the detection service |
| D6 | Authentication | Stateless JWT with Spring Security, BCrypt passwords |
| D7 | File storage | S3 behind a storage interface, local folder in development |
| D8 | Database and migrations | PostgreSQL with Flyway migrations |
| D9 | API style | REST with an OpenAPI file as the contract, RFC 7807 errors |
| D10 | Frontend | React, TypeScript, Vite |
| D11 | Hosting | AWS, CPU-only containers |
| D12 | Testing | JUnit and Testcontainers for the API, pytest for the detection service |

## D1. Detection model

- **Options:** train a custom model; fine-tune a pretrained model; use a pretrained model as is; call a vision API such as AWS Rekognition.
- **Decision:** use pretrained YOLOv8n through the Ultralytics package. COCO already includes person, car, motorcycle, bus and truck.
- **Why:** no labeling effort, runs on CPU, and self-hosted inference keeps cost predictable and shows real ML integration.
- **Cost:** no control over accuracy beyond the confidence threshold and frame stride. If precision on the evaluation set falls short of the 80% objective, fine-tuning on a small labeled set becomes the fallback.
- **License note:** Ultralytics distributes YOLOv8 under AGPL-3.0, with a separate commercial license. That is fine for coursework. A commercial deployment would need the commercial license or a different model. Confirm current terms before the final report.

## D2. Tracking

- **Options:** ByteTrack, BoT-SORT, DeepSORT, no tracking.
- **Decision:** use the tracker built into Ultralytics, ByteTrack by default.
- **Why:** it ships with the detector, so there is no extra dependency, and tracking is what lets the system log one event per object instead of one per frame.
- **Change from the charter:** the charter named DeepSORT as the fallback. BoT-SORT replaces it because it is built in; DeepSORT would require a separate library.

## D3. Processing mode

- **Options:** live RTSP streams; batch processing of uploaded recordings.
- **Decision:** batch processing of uploaded recordings.
- **Why:** live camera integration is out of scope in the charter, and batch processing is simpler to test and to evaluate against ground truth.
- **Cost:** the system does not alert in real time. The architecture does not rule out adding a streaming input later.

## D4. Backend to detection service

- **Options:** synchronous HTTP call and wait; message broker (SQS or RabbitMQ); HTTP submission with HTTP callback; detection service writing directly to the database.
- **Decision:** the API stores a job row, submits it over HTTP, and the detection service reports status and results by calling back.
- **Why:** a synchronous call would time out on long videos. A broker adds infrastructure that one developer must run and secure. Direct database writes would tie the detection service to the database schema.
- **Cost:** if the detection service is down, a queued job waits until someone retries it. A simple "retry stuck jobs" admin action or scheduled check covers this.

## D5. Where event rules run

- **Decision:** rules (loitering, zone entry, after-hours vehicle) run in the detection service, with zones and rules sent in each job.
- **Why:** rules need track data, which only the detection service has. Keeping configuration in the API database means one place to edit it.
- **Cost:** rule logic is in Python while configuration lives in Java. The job contract in the architecture document keeps them aligned.

## D6. Authentication

- **Options:** server sessions; stateless JWT; managed identity such as AWS Cognito.
- **Decision:** stateless JWT issued by the API, validated by Spring Security, with BCrypt-hashed passwords and a 60-minute token life.
- **Why:** simple to deploy behind a load balancer, and directly shows the security work the course asks for.
- **Cost:** tokens cannot be revoked before they expire. Refresh tokens and token revocation are out of scope; disabling a user takes effect when their token expires. Cognito is a possible future change.

## D7. File storage

- **Options:** database blobs; local disk; S3.
- **Decision:** S3 behind a `VideoStorage` interface, with a local-folder implementation for development and tests. Clips and thumbnails are served through short-lived signed links.
- **Why:** videos are large and do not belong in the database. The interface lets the project run locally without AWS.
- **Cost:** two implementations to maintain, and signed-link expiry must be handled in the UI.

## D8. Database and migrations

- **Options:** PostgreSQL; MongoDB.
- **Decision:** PostgreSQL, with Flyway migrations replacing the scaffold's `ddl-auto: update` before any real data is stored.
- **Why:** the data is relational (cameras, videos, jobs, events, users) and the search filters map directly to indexed SQL queries.
- **Cost:** schema changes need a migration file.

## D9. API style

- **Decision:** REST with `docs/api/openapi.yaml` as the contract. Errors follow RFC 7807 (Spring Boot's `ProblemDetail`).
- **Why:** the contract can be reviewed before the code exists and can generate API documentation. It supports the requirement that the API documentation match the implementation.
- **Cost:** the file must be kept in sync as the API changes.

## D10. Frontend

- **Decision:** React with TypeScript, built with Vite (already scaffolded). No UI framework yet.
- **Why:** it matches the existing full-stack experience and keeps the first version small. A component library can be added if time allows.

## D11. Hosting

- **Options:** AWS, GCP, Azure.
- **Decision:** AWS. The detection service runs on CPU, so no GPU instances are needed.
- **Why:** broad documentation, student credit programs, and personal familiarity. See the deployment mapping in the architecture document.
- **Cost:** credit availability is unconfirmed. The final service choices (Fargate or a single EC2 instance) are settled in Week 3.

## D12. Testing

- **Decision:** JUnit and Spring Boot tests for the API, Testcontainers with real PostgreSQL for repository and integration tests, pytest for detection logic, and the evaluation scripts for model precision.
- **Why:** testing against real PostgreSQL avoids differences from the in-memory H2 database used for quick local runs.
- **Cost:** CI needs Docker; GitHub-hosted runners provide it.
