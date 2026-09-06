.PHONY: dev dependencies stop test format build

dependencies:
	docker compose -f infrastructure/compose/compose.yaml up -d

dev: dependencies
	@trap 'kill 0' INT TERM EXIT; \
	(cd backend && ./gradlew bootRun --args='--spring.profiles.active=local') & \
	(cd frontend && npm run dev) & \
	wait

stop:
	docker compose -f infrastructure/compose/compose.yaml down

test:
	cd backend && ./gradlew test
	cd frontend && npm test && npm run lint && npm run build

format:
	cd backend && ./gradlew spotlessApply
	cd frontend && npm run lint

build:
	cd backend && ./gradlew clean build && ./gradlew cyclonedxBom --rerun-tasks
	cd frontend && npm run build
