package com.meridian.realestate.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the single-page application from the service itself, so the dashboard and the API share one
 * origin.
 *
 * <p><b>Why this class exists.</b> The React bundle is a client-routed SPA. A deep link such as
 * {@code /properties/3f1c…} is not a file on disk - it is a path the browser asks the server for and
 * that only the JavaScript router knows how to answer. Without a fallback the request reaches the
 * static resource handler, finds nothing, and the reader gets a 404 for a page the product does
 * serve. Forwarding an extension-less path to {@code index.html} lets the router take over.</p>
 *
 * <p><b>Why the patterns are depth-limited.</b> This is the part that is easy to get wrong. A greedy
 * {@code /**} fallback would also swallow {@code /api/v1/…}, and the API must not be swallowed:</p>
 *
 * <ul>
 *   <li>an <i>unknown</i> API path would answer 200 with an HTML shell instead of the product's JSON
 *       error envelope, so a caller and a monitoring probe would both see success where there is
 *       none;
 *   <li>a <i>wrong-method</i> call to a real endpoint would answer the shell instead of the 405 that
 *       {@code GlobalExceptionHandler} exists to produce.
 * </ul>
 *
 * <p>Every route the dashboard defines is one or two segments deep ({@code /dashboard},
 * {@code /properties/{id}}), and every API path is at least three ({@code /api/v1/…}). Restricting
 * the fallback to one and two segments therefore covers the SPA completely while leaving the whole
 * of {@code /api/} to the controllers and the exception handler, with no catch-all controller and no
 * change to the error contract. A file request is excluded by the {@code [^\.]*} segment pattern, so
 * {@code /assets/index-*.js} still reaches the resource handler.</p>
 *
 * <p>Handler-mapping order makes the rest safe: {@code RequestMappingHandlerMapping} runs before
 * view controllers, so a real endpoint always wins over this fallback regardless of pattern shape.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    // The root, which is the shell itself: `/` routes to /dashboard inside the app.
    registry.addViewController("/").setViewName("forward:/index.html");

    // Every register and top-level screen: /dashboard, /properties, /audit-logs, …
    registry.addViewController("/{page:[^\\.]*}").setViewName("forward:/index.html");

    // Every detail route: /properties/{id}, /leases/{id}, /leads/{id}.
    registry.addViewController("/{section:[^\\.]*}/{id:[^\\.]*}").setViewName("forward:/index.html");
  }
}
