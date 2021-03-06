/**
 * Framework X - Java Web Application Framework
 * Copyright (C) 2011 Robert Hollencamp
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.frameworkx.mvc;

import com.frameworkx.AbstractApplication;
import com.frameworkx.RouteHandler;
import com.thoughtworks.paranamer.CachingParanamer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang.WordUtils;

/**
 * Route handler that will dispatch a request to a Controller object
 *
 * @author Robert Hollencamp
 */
public class ControllerRouteHandler implements RouteHandler
{
	private static final CachingParanamer paranamer = new CachingParanamer();

	// static configuration
	private static String controllerPostfix;
	private static String defaultArea;

	private final String packageName;
	private final Object className;
	private final Object methodName;
	private final String areaName;

	/**
	 * Create a new controller route handler with the given package, class, method, and area
	 *
	 * @param packageName Package name as a String where controllers can be found for this route
	 * @param className Name of the controller class to use for this route. If an Integer object is
	 * passed, then the capture group of the route pattern will be used. If className is not an Integer,
	 * toString will be called and used as the class name
	 * @param methodName Name of the controller method to use for this route. If an Integer object is
	 * passed, then the capture group of the route pattern will be used. If methodName is not an Integer,
	 * toString will be called and used as the method name
	 * @param area Name of the area to use for this route handler. The route is used to separate templates;
	 * templates will be looked up using /Views/{Area Name}/{Controller Name}/{Method Name}.{extension}
	 */
	public ControllerRouteHandler(final String packageName, final Object className, final Object methodName, final String area)
	{
		if (packageName == null || packageName.isEmpty()) {
			throw new IllegalArgumentException("Package Name can not be null or empty");
		}
		this.packageName = packageName;

		if (className == null) {
			throw new IllegalArgumentException("Class Name can not be null");
		}
		this.className = className;

		if (methodName == null) {
			throw new IllegalArgumentException("Method Name can not be null");
		}
		this.methodName = methodName;

		if (area == null || area.isEmpty()) {
			throw new IllegalArgumentException("Area Name can not be null or empty");
		}
		this.areaName = area;
	}

	/**
	 * Create a new controller route handler with the given package, class, and method
	 *
	 * @param packageName Package name as a String where controllers can be found for this route
	 * @param className Name of the controller class to use for this route. If an Integer object is
	 * passed, then the capture group of the route pattern will be used. If className is not an Integer,
	 * toString will be called and used as the class name
	 * @param methodName Name of the controller method to use for this route. If an Integer object is
	 * passed, then the capture group of the route pattern will be used. If methodName is not an Integer,
	 * toString will be called and used as the method name
	 */
	public ControllerRouteHandler(final String packageName, final Object className, final Object methodName)
	{
		if (packageName == null || packageName.isEmpty()) {
			throw new IllegalArgumentException("Package Name can not be null or empty");
		}
		this.packageName = packageName;

		if (className == null) {
			throw new IllegalArgumentException("Class Name can not be null");
		}
		this.className = className;

		if (methodName == null) {
			throw new IllegalArgumentException("Method Name can not be null");
		}
		this.methodName = methodName;

		this.areaName = defaultArea;
	}

	/**
	 * Given a method, try and fill in any String arguments from the request (GET, POST)
	 *
	 * @param method
	 * @param request
	 * @return
	 */
	private Object[] getMethodArgs(final Method method, final HttpServletRequest request)
	{
		// get parameter names
		String[] paramNames;
		synchronized (paranamer) {
			paramNames = paranamer.lookupParameterNames(method, false);
		}
		// unable to get parameter names
		if (paramNames == null) {
			// @todo log this
			return new Object[]{};
		}

		Class[] paramTypes = method.getParameterTypes();
		List<Object> ret = new ArrayList<Object>(paramTypes.length);

		for (int i = 0; i < paramTypes.length; i++) {
			if (paramTypes[i].equals(String.class)) {
				ret.add(request.getParameter(paramNames[i]));
			} else {
				ret.add(null);
			}
		}

		return ret.toArray();
	}

	/**
	 * Determine the name of the controller class. If an Integer was specified
	 * for class name, use that as a capture group ref. Otherwise toString it
	 *
	 * @param m
	 * @return
	 */
	private String determineClassName(final Matcher m)
	{
		String ret;
		if (this.className instanceof Integer) {
			ret = m.group((Integer) this.className) + controllerPostfix;
		} else {
			ret = this.className.toString() + controllerPostfix;
		}
		return WordUtils.capitalize(ret);
	}

	/**
	 * Determine the name of the controller method. If an Integer was specified
	 * for the method name, use that as a capture group ref. Otherwise toString it
	 *
	 * @param m
	 * @return
	 */
	private String determineMethodName(final Matcher m)
	{
		if (this.methodName instanceof Integer) {
			return m.group((Integer) this.methodName);
		}
		return this.methodName.toString();
	}

	/**
	 * Determine what method from the controller class to call
	 *
	 * @param methodName
	 * @param clss
	 * @return
	 */
	private Method chooseMethod(final String methodName, final Class<? extends Controller> clss, final HttpServletRequest request)
	{
		for (Method func : clss.getMethods()) {
			if (func.getName().equals(methodName)) {
				// check HTTP method
				HttpMethod annotation = func.getAnnotation(HttpMethod.class);
				if (annotation != null) {
					if (annotation.value() != null && !annotation.value().isEmpty()) {
						final String httpMethod = request.getMethod();
						if (!annotation.value().contains(httpMethod)) {
							continue;
						}
					}
				}

				return func;
			}
		}
		return null;
	}

	/**
	 * Handle a request
	 *
	 * @param request
	 * @param response
	 * @param m
	 */
	public void execute(final HttpServletRequest request, final HttpServletResponse response, final Matcher m)
	{
		try {
			Class<? extends Controller> controllerClass = Class.forName(this.packageName + "." + this.determineClassName(m)).asSubclass(Controller.class);
			Controller controller = controllerClass.getConstructor().newInstance();

			Method controllerMethod = chooseMethod(this.determineMethodName(m), controllerClass, request);
			if (controllerMethod == null) {
				// @todo
				return;
			}

			// store chosen values in the request
			request.setAttribute("com.frameworkx.controllerMethod", controllerMethod);
			request.setAttribute("com.frameworkx.controller", controller);
			request.setAttribute("com.frameworkx.controllerArea", this.areaName);

			Result result = (Result) controllerMethod.invoke(controller, getMethodArgs(controllerMethod, request));
			result.execute(request, response);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Initialize static config when application starts
	 *
	 * Called automatically by framework in initialization; should never be called from user code
	 *
	 * @param app
	 */
	public static void init(final AbstractApplication app)
	{
		defaultArea = app.getProperty("mvc.defaultArea");
		if (defaultArea != null && defaultArea.isEmpty()) {
			defaultArea = null;
		}

		controllerPostfix = app.getProperty("mvc.controllerPostfix");
	}
}
