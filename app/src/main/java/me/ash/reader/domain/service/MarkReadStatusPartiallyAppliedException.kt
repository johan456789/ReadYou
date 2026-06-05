package me.ash.reader.domain.service

class MarkReadStatusPartiallyAppliedException(
    val affectedIds: Set<String>,
    cause: Throwable,
) : RuntimeException(cause)
