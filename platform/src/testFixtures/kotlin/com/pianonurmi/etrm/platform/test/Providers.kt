package com.pianonurmi.etrm.platform.test

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory

/** `ObjectProvider`s for services wired by hand in tests, as Spring would hand them over. */
object Providers {
    /** A provider with no bean behind it: `ifAvailable` is null and the stream empty. */
    inline fun <reified T : Any> none(): ObjectProvider<T> = StaticListableBeanFactory().getBeanProvider(T::class.java)

    /** A provider of the given beans, in the given order. */
    inline fun <reified T : Any> of(vararg beans: T): ObjectProvider<T> = StaticListableBeanFactory(
        beans.withIndex().associate { (i, bean) -> "bean$i" to bean },
    ).getBeanProvider(T::class.java)
}
