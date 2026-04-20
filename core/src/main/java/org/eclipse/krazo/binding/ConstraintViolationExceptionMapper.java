/*
 * Copyright (c) 2018, 2026 Eclipse Krazo committers and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.eclipse.krazo.binding;

import jakarta.annotation.Priority;
import jakarta.mvc.Controller;
import jakarta.mvc.MvcContext;
import jakarta.mvc.View;
import jakarta.mvc.binding.MvcBinding;
import jakarta.mvc.binding.ValidationError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import org.eclipse.krazo.binding.validate.ConstraintViolationMetadata;
import org.eclipse.krazo.binding.validate.ConstraintViolations;
import org.eclipse.krazo.engine.Viewable;
import org.eclipse.krazo.util.AnnotationUtils;
import org.eclipse.krazo.util.CdiUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A portable {@link ExceptionMapper} for {@link ConstraintViolationException} that handles
 * MVC controller validation failures. On JAX-RS runtimes that do not have a Krazo-specific
 * mechanism to suppress built-in bean validation for MVC controllers (such as OpenLiberty/CXF),
 * this mapper catches the exception, populates {@link jakarta.mvc.binding.BindingResult},
 * and invokes the controller method so it can handle the validation errors.
 *
 * <p>For non-MVC requests, this mapper returns a standard 400 Bad Request response.</p>
 *
 * @author Eclipse Krazo
 */
@Priority(Priorities.USER - 100) // higher priority than default to handle before runtime's built-in mapper
public class ConstraintViolationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    private static final Logger log = Logger.getLogger(ConstraintViolationExceptionMapper.class.getName());

    private static final String REDIRECT_PREFIX = "redirect:";

    @Context
    private ResourceInfo resourceInfo;

    @Context
    private UriInfo uriInfo;

    @Override
    public Response toResponse(ConstraintViolationException exception) {

        if (resourceInfo == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        Class<?> resourceClass = resourceInfo.getResourceClass();
        Method method = resourceInfo.getResourceMethod();

        if (resourceClass == null || method == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        boolean mvcClass = AnnotationUtils.hasAnnotation(resourceClass, Controller.class);
        boolean mvcMethod = AnnotationUtils.hasAnnotation(method, Controller.class);

        if (!mvcClass && !mvcMethod) {
            // Not an MVC controller - return standard 400 response
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        return handleMvcController(exception, resourceClass, method);
    }

    private Response handleMvcController(ConstraintViolationException exception,
                                         Class<?> resourceClass, Method method) {
        try {
            BindingResultImpl bindingResult = CdiUtils.getApplicationBean(BindingResultImpl.class)
                .orElseThrow(() -> new IllegalStateException("Cannot find CDI managed BindingResultImpl"));

            MvcContext mvcContext = CdiUtils.getApplicationBean(MvcContext.class)
                .orElseThrow(() -> new IllegalStateException("Cannot find CDI managed MvcContext"));

            ConstraintViolationTranslator translator = CdiUtils.getApplicationBean(ConstraintViolationTranslator.class)
                .orElseThrow(() -> new IllegalStateException("Cannot find CDI managed ConstraintViolationTranslator"));

            // Populate BindingResult with constraint violations
            Set<ValidationError> validationErrors = new LinkedHashSet<>();
            for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
                ConstraintViolationMetadata metadata = ConstraintViolations.getMetadata(violation);

                if (metadata.hasAnnotation(MvcBinding.class)) {
                    String paramName = metadata.getParamName().orElse(null);
                    if (paramName == null) {
                        log.log(Level.WARNING, "Cannot resolve paramName for violation: {0}", violation);
                    }
                    String message = translator.translate(violation, mvcContext.getLocale());
                    validationErrors.add(new ValidationErrorImpl(violation, paramName, message));
                }
            }

            if (!validationErrors.isEmpty()) {
                log.log(Level.FINE, "Adding {0} validation errors to binding result from ExceptionMapper",
                    validationErrors.size());
                bindingResult.addValidationErrors(validationErrors);
            }

            // Mark that validation was already performed externally
            bindingResult.setValidationPerformedExternally(true);

            // Invoke the controller method via CDI to get the view name
            Object controller = CdiUtils.newBean(CdiUtils.getApplicationBeanManager(), resourceClass);
            Object[] params = createDefaultParams(method);
            method.setAccessible(true);
            Object result = method.invoke(controller, params);

            return processControllerResult(result, method);

        } catch (InvocationTargetException e) {
            log.log(Level.SEVERE, "Controller method threw an exception during MVC validation handling", e.getCause());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Error handling MVC ConstraintViolationException", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    private Response processControllerResult(Object result, Method method) {
        String viewName = null;

        if (result instanceof Response) {
            return (Response) result;
        } else if (result instanceof String) {
            viewName = (String) result;
        } else if (result == null) {
            // void method or null return - check for @View annotation
            View view = AnnotationUtils.getAnnotation(method, View.class);
            if (view == null) {
                view = AnnotationUtils.getAnnotation(method.getDeclaringClass(), View.class);
            }
            if (view != null) {
                viewName = view.value();
            }
        }

        if (viewName != null) {
            if (viewName.startsWith(REDIRECT_PREFIX)) {
                String redirectPath = viewName.substring(REDIRECT_PREFIX.length());
                URI baseUri = uriInfo != null ? uriInfo.getBaseUri() : null;
                String uri = baseUri != null
                    ? baseUri.toString() + redirectPath.replaceFirst("^/", "")
                    : redirectPath;
                return Response.seeOther(URI.create(uri)).build();
            }
            return Response.ok(new Viewable(viewName)).type(MediaType.TEXT_HTML_TYPE).build();
        }

        return Response.status(Response.Status.BAD_REQUEST).build();
    }

    private static Object[] createDefaultParams(Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] params = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            params[i] = getDefaultValue(paramTypes[i]);
        }
        return params;
    }

    private static Object getDefaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }
}
