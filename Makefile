.PHONY: dev dependencies frontend-dependencies stop test format build toolchains

toolchains:
	@printf 'Java runtime: '
	@java -version 2>&1 | head -n 1
	@printf 'Node runtime: '
	@node --version
	@printf 'Docker Compose: '
	@docker compose version
	@cd backend && ./gradlew -q javaToolchains

dependencies:
	docker compose -f infrastructure/compose/compose.yaml up -d --wait postgres kafka redis minio mailpit otel-collector
	docker compose -f infrastructure/compose/compose.yaml run --rm kafka-init
	docker compose -f infrastructure/compose/compose.yaml run --rm minio-init

frontend-dependencies:
	@test -d frontend/node_modules || (cd frontend && npm ci)

dev: dependencies frontend-dependencies
	@backend_pid=; frontend_pid=; \
	trap 'test -z "$$backend_pid" || kill "$$backend_pid" 2>/dev/null; test -z "$$frontend_pid" || kill "$$frontend_pid" 2>/dev/null' EXIT; \
	trap 'exit 130' INT TERM; \
	(cd backend && exec ./gradlew --no-daemon bootRun --args='--spring.profiles.active=local') & backend_pid=$$!; \
	(cd frontend && exec npm run dev) & frontend_pid=$$!; \
	while kill -0 "$$backend_pid" 2>/dev/null && kill -0 "$$frontend_pid" 2>/dev/null; do sleep 1; done; \
	if ! kill -0 "$$backend_pid" 2>/dev/null; then wait "$$backend_pid"; else wait "$$frontend_pid"; fi

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
