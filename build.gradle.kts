tasks.register("test") {
    group = "verification"
    description = "Runs capability contract tests and MCP server tests."
    dependsOn(":documents:jvmTest", ":telegram:jvmTest", ":mcp-server:test")
}
