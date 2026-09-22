/**
 * The application's request-body vocabulary — application-facing: {@code BodyHandler<T>}
 * is the handler type of a body-consuming route (the body is already
 * deserialized to {@code T} when {@code handle} runs), {@code MultipartForm}
 * parses multipart submissions, and the exceptions here
 * ({@code BodyTooLargeException}, {@code MultipartException},
 * {@code UnsupportedMediaTypeException}) are the failures an application's
 * {@code ErrorHandler} maps. The streams that read raw bytes live behind
 * the seam in {@code engine}.
 */
package com.jujin.freeway.http.body;
