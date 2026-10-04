.PHONY: clean check format abi test functional-test docs publish-local help
.DEFAULT_GOAL := help

clean: ## Remove build outputs
	@./gradlew clean $(GRADLE_ARGS)

check: ## Run checks
	@./gradlew check $(GRADLE_ARGS)

format: ## Format code and build scripts
	@./gradlew ktlintFormat sortDependencies $(GRADLE_ARGS)

abi: ## Update Kotlin ABI files
	@./gradlew updateKotlinAbi $(GRADLE_ARGS)

test: ## Run unit tests
	@./gradlew test $(GRADLE_ARGS)

functional-test: ## Run functional tests
	@./gradlew functionalTest $(GRADLE_ARGS)

docs: ## Generate API documentation
	@./gradlew :dokkaGenerate $(GRADLE_ARGS)

publish-local: ## Publish artifacts to Maven Local
	@./gradlew publishToMavenLocal $(GRADLE_ARGS)

help: ## Show this help
	@printf 'Available targets:\n'
	@color=''; reset=''; \
	if [ -t 1 ] && [ "$${TERM:-dumb}" != dumb ] && [ -z "$${NO_COLOR:-}" ]; then \
		color=$$(printf '\033[36m'); reset=$$(printf '\033[0m'); \
	fi; \
	awk -v color="$$color" -v reset="$$reset" '/^[[:alnum:]_-]+:/ { target = $$0; sub(/:.*/, "", target); description = $$0; if (sub(/.*##[[:space:]]*/, "", description) == 0) description = ""; printf "  %s%s%s%*s %s\n", color, target, reset, 17 - length(target), "", description }' $(MAKEFILE_LIST)
	@printf '\nPass Gradle options with GRADLE_ARGS, e.g. make check GRADLE_ARGS="--info --rerun-tasks"\n'
