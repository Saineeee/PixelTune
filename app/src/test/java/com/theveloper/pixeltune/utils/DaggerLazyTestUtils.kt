package com.theveloper.pixeltune.utils

/**
 * Wraps a value in a [dagger.Lazy] for constructor calls in tests where the
 * production code takes dagger.Lazy dependencies to defer heavy construction.
 */
fun <T> daggerLazyOf(value: T): dagger.Lazy<T> = object : dagger.Lazy<T> {
    override fun get(): T = value
}
