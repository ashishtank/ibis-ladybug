/*
   Copyright 2021-2022 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.nn.testtool.web;

import java.io.IOException;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

/**
 * <p>
 * Serve an Angular app from a WebJars jar using the version agnostic approach when a version is not specified, serving
 * index.html when a resource is not found and changing the base href in the index.html when needed.
 * <p>
 * This servlets relies on WebJars being configured for the webapp it is running in as it will dispatch requests to
 * /webjars/ for WebJars resources. Information about WebJars and how to configure them for your webapp can be found at:
 * <ul>
 *   <li>https://www.webjars.org/</li>
 * </ul>
 * <p>
 * The Angural website states: "Routed apps must fallback to index.html. ... If the application uses the Angular router,
 * you must configure the server to return the application's host page (index.html) when asked for a file that it does
 * not have.". For more information read:
 * <ul>
 *   <li>https://angular.io/guide/deployment#server-configuration</li>
 *   <li>https://angular.io/guide/router#locationstrategy-and-browser-url-styles</li>
 *   <li>https://angular.io/guide/router#choosing-a-routing-strategy</li>
 *   <li>https://angular.io/guide/router#base-href-1</li>
 * </ul>
 * <p>
 * Depending on how servlets are configurated the Angular app might be served from the root context
 * (http://&lt;hostname&gt;/) or a different context (e.g. http://&lt;hostname&gt;/my-app/) in which case the index.html of
 * the Angular app should contain &lt;base href="/my-app/"&gt; instead of &lt;base href="/"&gt;. To make it possible to use
 * the same WebJars jar for servlet configurations with different servlet mappings this Angular servlet will adjust
 * the value of the href attribute of the base element to correspond with the serverside configured context path and
 * servlet path when serving the index.html.
 * 
 * @author Jaco de Groot
 */
public class AngularServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private String artifactId;
	private String version = "";

	/**
	 * Set artifactId of WebJars jar that contains the Angular app to be served. In case of a Maven project the pom.xml
	 * of the project should also contain this artifactId.
	 * 
	 * @param artifactId  artifactId of WebJars jar that contains the Angular app to be served
	 */
	public void setArtifactId(String artifactId) {
		this.artifactId = artifactId;
	}

	/**
	 * Set version of WebJars jar that contains the Angular app to be served. In case of a Maven project the pom.xml
	 * of the project should also contain this version. The version agnostic approach is used when version is not set.
	 * 
	 * @param version  version of WebJars jar that contains the Angular app to be served
	 */
	public void setVersion(String version) {
		this.version = "/" + version;
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		HttpServletResponseWrapper responseWrapper = new HttpServletResponseWrapper(response) {
			@Override
			public void sendError(int sc) throws IOException {
				if (sc == HttpServletResponse.SC_NOT_FOUND) {
					try {
						// Write index.html when resource not found
						includeWebJarAsset(request, response, true);
					} catch (ServletException e) {
						super.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
								"Got exception reading index.html for " + artifactId + ": " + e.getMessage());
					}
				} else {
					super.sendError(sc);
				}
			}
		};
		includeWebJarAsset(request, responseWrapper, false);
	}

	private void includeWebJarAsset(HttpServletRequest request, HttpServletResponse response, boolean forceIndexHtml)
			throws ServletException, IOException {
		String pathInfo = request.getPathInfo();
		// Use endsWith("/") instead of equals("/") because the WebjarsServlet returns HttpServletResponse.SC_FORBIDDEN
		// for directory requests
		if (pathInfo == null || pathInfo.endsWith("/") || forceIndexHtml) {
			pathInfo = "/index.html";
		}
		if ("/index.html".equals(pathInfo)) {
			// Replace the value of <base href="/"> in index.html with the context path and servlet path
			String path = request.getContextPath() + request.getServletPath();
			final String base;
			if (path.equals("")) {
				base = "/";
			} else if (!path.endsWith("/")) {
				base = path + "/";
			} else {
				base = path;
			}
			response = new HttpServletResponseWrapper(response) {
				@Override
				public ServletOutputStream getOutputStream() throws IOException {
					BaseRewritingServletOutputStream baseRewritingServletOutputStream =
							new BaseRewritingServletOutputStream(super.getOutputStream(), base);
					return baseRewritingServletOutputStream;
				}
			};
		}
		final String webJarsBase = "/webjars/";
		final String webJarsRequestURI;
		if (pathInfo.startsWith(webJarsBase)) {
			webJarsRequestURI = pathInfo;
		} else {
			webJarsRequestURI = webJarsBase + artifactId + version + pathInfo;
		}
		// When Servlet 3 method (see https://www.webjars.org/documentation#servlet3) is used the Content-Type header
		// isn't set (tested with Tomcat 9.0.60) which will cause problems when X-Content-Type-Options: nosniff is begin
		// used. Hence set the header like it is done by WebJars Servlet 2
		// (https://www.webjars.org/documentation#servlet2)
		String[] tokens = webJarsRequestURI.split("/");
		String filename = tokens[tokens.length - 1];
		String mimeType = getServletContext().getMimeType(filename);
		response.setContentType(mimeType != null ? mimeType : "application/octet-stream");
		HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(request) {
			@Override
			public String getServletPath() {
				return webJarsBase;
			}
			@Override
			public String getRequestURI() {
				return webJarsRequestURI;
			}
		};
		RequestDispatcher requestDispatcher = request.getRequestDispatcher(webJarsRequestURI);
		requestDispatcher.include(requestWrapper, response);
	}
}

class BaseRewritingServletOutputStream extends ServletOutputStream {
	ServletOutputStream servletOutputStream;
	String newBase;
	int i = -1;
	int phase = 1;
	StringBuffer stringBuffer = new StringBuffer();
	
	BaseRewritingServletOutputStream(ServletOutputStream servletOutputStream, String newBase) {
		this.servletOutputStream = servletOutputStream;
		this.newBase = newBase;
		i = 0;
	}

	@Override
	public boolean isReady() {
		return servletOutputStream.isReady();
	}

	@Override
	public void setWriteListener(WriteListener writeListener) {
		servletOutputStream.setWriteListener(writeListener);
		
	}

	@Override
	public void write(int b) throws IOException {
		if (i != -1) {
			if (phase == 2 && (char)b != '"') {
				// Discard char from old base
			} else {
				servletOutputStream.write(b);
				stringBuffer.append((char)b);
				if (phase == 2 && (char)b == '"') {
					i = -1;
				} else {
					if (endsWith(stringBuffer, "<base href=\"")) {
						for (int i = 0; i < newBase.length(); i++) {
							servletOutputStream.write(newBase.charAt(i));
						}
						phase = 2;
					}
				}
			}
		} else {
			servletOutputStream.write(b);
		}
	}

	private boolean endsWith(StringBuffer stringBuffer, String string) {
		if (stringBuffer.length() >= string.length()) {
			for (int i = 0; i < string.length(); i++) {
				if (!(string.charAt(i) == stringBuffer.charAt(stringBuffer.length() - string.length() + i))) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

}
