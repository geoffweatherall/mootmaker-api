#!/usr/bin/env bash
#
# Compiler diagnostics that Maven does not give us.
#
# javac has no unused-import warning at all, so `mvn test` stays green with dead imports in the
# source. The editor DOES report them, because VS Code's Java support is Eclipse JDT rather than
# javac - which is how they get noticed one file at a time, whenever someone happens to open one.
# This runs the same Eclipse compiler over every file, so CI sees what the editor sees.
#
# It is deliberately not `mvn compile`. ECJ here only diagnoses (-d none writes no class files);
# javac remains the compiler that builds the artifact.
#
# Note this catches something google-java-format does not: an unused `import module java.base`.
# The formatter strips ordinary unused imports but leaves module imports alone, so without this
# step the Java 25 case - the one that started all of it - would go unnoticed.

set -euo pipefail

readonly ECJ_VERSION="3.46.100"
readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Which warnings fail the build. All five are at zero, so anything new is genuinely new.
# Kept byte-identical to the copy in the other Java repository, so drift shows up in a plain diff.
# If they ever have to differ, say why here.
readonly WARNINGS="+unusedImport,+unusedLocal,+unusedPrivateMember,+unusedParam,+deprecation"

ecj_jar() {
    local jar="${HOME}/.m2/repository/org/eclipse/jdt/ecj/${ECJ_VERSION}/ecj-${ECJ_VERSION}.jar"
    if [[ ! -f "$jar" ]]; then
        echo "Fetching ecj ${ECJ_VERSION}..." >&2
        mvn -q dependency:get -Dartifact="org.eclipse.jdt:ecj:${ECJ_VERSION}" >&2
    fi
    echo "$jar"
}

lint_module() {
    local module="$1"
    local pom="${REPO_ROOT}/${module}/pom.xml"
    local cp_file
    cp_file="$(mktemp)"

    mvn -q -f "$pom" dependency:build-classpath \
        -Dmdep.outputFile="$cp_file" -Dmdep.includeScope=test >&2

    local sources=()
    [[ -d "${REPO_ROOT}/${module}/src/main/java" ]] && sources+=("${REPO_ROOT}/${module}/src/main/java")
    [[ -d "${REPO_ROOT}/${module}/src/test/java" ]] && sources+=("${REPO_ROOT}/${module}/src/test/java")

    local output
    # ECJ reports diagnostics on stderr and exits 0 for warnings, so the exit code cannot be the
    # signal - the presence of output is. Captured rather than streamed for exactly that reason.
    output="$(java -jar "$(ecj_jar)" \
        --release 25 -proc:none -d none -nowarn -warn:"${WARNINGS}" \
        -classpath "$(cat "$cp_file")" \
        "${sources[@]}" 2>&1 || true)"
    rm -f "$cp_file"

    if grep -q 'WARNING in\|ERROR in' <<<"$output"; then
        echo "$output"
        echo
        echo "FAIL: ${module} has compiler diagnostics above."
        return 1
    fi
    echo "  ${module}: clean"
}

failed=0
echo "Eclipse compiler diagnostics (${WARNINGS})"
for module in impl verify; do
    lint_module "$module" || failed=1
done

if (( failed )); then
    echo
    echo "Unused imports are removed by 'mvn spotless:apply' - except 'import module' ones," >&2
    echo "which have to go by hand." >&2
    exit 1
fi
echo "All modules clean."
