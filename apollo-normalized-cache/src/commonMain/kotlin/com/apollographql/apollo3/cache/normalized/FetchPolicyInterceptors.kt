@file:JvmName("FetchPolicyInterceptors")

package com.apollographql.apollo3.cache.normalized

import com.apollographql.apollo3.api.ApolloRequest
import com.apollographql.apollo3.api.ApolloResponse
import com.apollographql.apollo3.api.Operation
import com.apollographql.apollo3.api.Query
import com.apollographql.apollo3.exception.ApolloException
import com.apollographql.apollo3.interceptor.ApolloInterceptor
import com.apollographql.apollo3.interceptor.ApolloInterceptorChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlin.jvm.JvmName

/**
 * An interceptor that goes to the cache only
 */
val CacheOnlyInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return chain.proceed(
        request = request
            .newBuilder()
            .fetchFromCache(true)
            .build()
    )
  }
}

val NetworkOnlyInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return chain.proceed(request)
  }
}

/**
 * An interceptor that goes to cache first and then to the network if it fails
 */
val CacheFirstInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return flow {
      val cacheResponse = chain.proceed(
          request = request
              .newBuilder()
              .fetchFromCache(true)
              .build()
      ).single()

      if (cacheResponse.exception == null) {
        emit(cacheResponse)
        return@flow
      }

      val networkResponses = chain.proceed(
          request = request
      )
          .map { response ->
            response.newBuilder()
                .cacheInfo(
                    response.cacheInfo!!
                        .newBuilder()
                        .cacheMissException(cacheResponse.cacheInfo!!.cacheMissException)
                        .build()
                )
                .build()
          }

      emitAll(networkResponses)
    }
  }
}

val NetworkFirstInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return flow {
      var networkException: ApolloException? = null

      chain.proceed(
          request = request
      ).collect {
        networkException = it.exception
        emit(it)
      }
      if (networkException == null) return@flow

      val cacheResponse = chain.proceed(
          request = request
              .newBuilder()
              .fetchFromCache(true)
              .build()
      ).single()

      emit(
          cacheResponse.newBuilder()
              .cacheInfo(
                  cacheResponse.cacheInfo!!
                      .newBuilder()
                      .networkException(networkException)
                      .build()
              )
              .build()
      )
      return@flow
    }
  }
}

/**
 * An interceptor that goes to cache first and then to the network.
 * An exception is not thrown if the cache fails, whereas an exception will be thrown upon network failure.
 */
val CacheAndNetworkInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return flow {
      val cacheResponse = chain.proceed(
          request = request
              .newBuilder()
              .fetchFromCache(true)
              .build()
      ).single()
      emit(cacheResponse.newBuilder().isLast(false).build())

      val networkResponses = chain.proceed(request)
          .map { response ->
            response.newBuilder()
                .cacheInfo(
                    response.cacheInfo!!
                        .newBuilder()
                        .cacheMissException(cacheResponse.cacheInfo!!.cacheMissException)
                        .build()
                )
                .build()
          }

      emitAll(networkResponses)
    }
  }
}

internal val FetchPolicyRouterInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    if (request.operation !is Query) {
      // Subscriptions and Mutations do not support fetchPolicies
      return chain.proceed(request)
    }
    return request.fetchPolicyInterceptor.intercept(request, chain)
  }
}
