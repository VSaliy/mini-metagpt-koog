package io.github.vsaliy.minimetagpt.infrastructure.tools

/** A tool refused the request (bad argument, path escape, disallowed command). */
class ToolRejectedException(message: String) : RuntimeException(message)

/** A tool failed while executing an otherwise valid request. */
class ToolExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
