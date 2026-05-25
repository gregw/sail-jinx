package org.mortbay.sailing.jinx.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves static resources packaged under {@code /static/} on the classpath.
 *
 * <p>Resolves {@code <!-- INCLUDE foo.html -->} directives in HTML responses, so
 * pages can share a common nav fragment without a templating engine. The
 * pattern is borrowed from sailing-pf's StaticResourceServlet — same convention,
 * smaller surface.
 */
public class StaticResourceServlet extends HttpServlet
{
    private static final Pattern INCLUDE = Pattern.compile("<!--\\s*INCLUDE\\s+(\\S+)\\s*-->");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        String path = req.getPathInfo();
        if (path == null || "/".equals(path))
            path = "/index.html";

        try (InputStream in = getClass().getResourceAsStream("/static" + path))
        {
            if (in == null)
            {
                resp.sendError(404);
                return;
            }
            resp.setContentType(guessContentType(path));
            if (path.endsWith(".html"))
            {
                String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                resp.getWriter().write(resolveIncludes(html));
            }
            else
            {
                in.transferTo(resp.getOutputStream());
            }
        }
    }

    private String resolveIncludes(String html) throws IOException
    {
        Matcher m = INCLUDE.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find())
        {
            String file = m.group(1);
            String replacement = "";
            try (InputStream inc = getClass().getResourceAsStream("/static/" + file))
            {
                if (inc != null)
                    replacement = new String(inc.readAllBytes(), StandardCharsets.UTF_8);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String guessContentType(String path)
    {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=UTF-8";
        if (path.endsWith(".css"))  return "text/css; charset=UTF-8";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".ico"))  return "image/x-icon";
        return "application/octet-stream";
    }
}
