/**
 * The pipeline's filtering half — application-facing: {@code HttpFilter} is
 * the contract a filter implements, {@code ErrorHandler} maps an unhandled
 * exception to a response, {@code CorsFilter}, {@code HealthFilter} and
 * {@code AccessLogFilter} are the built-ins, and {@code HealthCheck} is
 * what an application contributes for liveness. Filters run in the
 * compiled pipeline ahead of the exchange seam and never see engine
 * internals.
 */
package com.jujin.freeway.http.filter;
