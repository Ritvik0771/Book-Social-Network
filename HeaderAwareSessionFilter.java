package com.ncr.cxp.services.security;

import com.hazelcast.core.EntryView;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.ncr.cxp.api.language.dto.Language;
import com.ncr.cxp.api.language.dto.response.ReadLanguageResponse;
import com.ncr.cxp.common.exception.BusinessException;
import com.ncr.cxp.framework.profile.ProfileUtils;
import com.ncr.cxp.framework.security.CxpAuthenticationToken;
import com.ncr.cxp.provisioning.ConfigurationSettingData;
import com.ncr.cxp.provisioning.user.PreferencesUserSettingsService;
import com.ncr.cxp.provisioning.user.SetSettingsForUserRequest;
import com.ncr.cxp.provisioning.user.UserService;
import com.ncr.cxp.security.authn.repository.UserSessionRepository;
import com.ncr.cxp.services.language.api.LanguageService;
import com.ncr.cxp.services.security.events.LoginFailureHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.GenericFilterBean;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A custom Filter to be included as part of the Spring Security Filter chain to expire the session after a period of
 * time (defined in the web.xml), with the ability to prevent certain requests from keeping the session alive based on
 * the existence of a configurable HTTP Header.
 *
 * @author sb185196
 */
public class HeaderAwareSessionFilter extends GenericFilterBean {

    /** The hazelcast map mame where the sessions are stored */
    public static final String HZ_MAP_NAME_FOR_CXP_SESSION = "cxpHzSessions";

    /** Url path for the spring security check. */
    private static final String J_SPRING_SECURITY_CHECK = "j_spring_security_check";

    /** The Key to use to store and retrieve the session attribute */
    private static final String KEY = "CXP_SESSION_REFRESHED";

    /** The Logger to use */
    private static final Logger LOGGER = LoggerFactory.getLogger(HeaderAwareSessionFilter.class);

    /** The forward proxy */
    @Value("${com.ncr.cxp.forward.proxy:#{null}}")
    private String forwardProxy;

    /** flag to indicate if the oldest session must be invalidated. */
    @Value("${com.ncr.cxp.session.expire.oldest.session:true}")
    private boolean expireOldestSession;

    /** A handle to the {@link HazelcastInstance}. */
    private final HazelcastInstance hazelcastInstance;

    /** A handle to the {@link LanguageService} to use. */
    private final LanguageService languageService;

    /** A handle to the {@link PreferencesUserSettingsService} to use. */
    private final PreferencesUserSettingsService preferencesUserSettingsService;

    /** The number of user sessions permitted */
    @Value("${com.ncr.cxp.session.maximumSessions:-1}")
    private int maximumSessions;

    /** The name of the header to check for */
    private final String noRefreshHeaderName;

    /**
     * A handle to the {@link SessionRegistry} to get session information such as IDs and principals.
     */
    private final SessionRegistry sessionRegistry;

    /** A handle to the {@link UserSessionRepository} to delete the token. */
    private final UserSessionRepository userSessionRepository;

    /** A handle to the {@link Language}. */
    private Language language = new Language();

    /** The upload file name pattern. */
    @Value("${com.ncr.cxp.session.maxIdleTime:1800}")
    private Long maxSessionIdleTime;

    /** The UserService object. */
    @Autowired
    private UserService userService;

    /**
     * Creates a new {@link HeaderAwareSessionFilter}
     *
     * @param noRefreshHeaderName   The name of the header that will skip header refreshing
     * @param languageService       A handle to the {@link LanguageService} to use.
     * @param sessionRegistry       A handle to the {@link SessionRegistry} to use.
     * @param userSessionRepository A handle to the {@link UserSessionRepository} to use.
     * @param hazelcastInstance     A handle to the {@link HazelcastInstance} to use.
     */
    @Autowired
    public HeaderAwareSessionFilter(final String noRefreshHeaderName, final LanguageService languageService,
            final SessionRegistry sessionRegistry, final UserSessionRepository userSessionRepository,
            final HazelcastInstance hazelcastInstance, PreferencesUserSettingsService preferencesUserSettingsService) {
        Assert.hasText(noRefreshHeaderName, "Header name is required!");
        this.noRefreshHeaderName = noRefreshHeaderName;
        this.languageService = languageService;
        this.sessionRegistry = sessionRegistry;
        this.userSessionRepository = userSessionRepository;
        this.hazelcastInstance = hazelcastInstance;
        this.preferencesUserSettingsService = preferencesUserSettingsService;
    }

    // @formatter:off
    @Override// NOSONAR - Spring's GenericFilterBean doFilter throws more than 1 exception. Conforming to their convention.
    // @formatter:on
    public void doFilter(final ServletRequest req, final ServletResponse res, final FilterChain chain)
            throws IOException, ServletException {
        final HttpServletRequest request = (HttpServletRequest) req;
        final HttpServletResponse response = (HttpServletResponse) res;
        HttpSession session = request.getSession(false);

        // Check if language was set on login
        final String selectedLanguage = request.getParameter("language");

        // To create a session when language and organization selections are available but no session was found.
        if (StringUtils.hasText(selectedLanguage) && session == null) {
            session = request.getSession(true);
        }

        if (StringUtils.hasText(selectedLanguage) && session != null && this.languageService.isLanguageAvailable(
                selectedLanguage)) {
            // Persist login language selection for processing in DashboardWebController
            session.setAttribute("selectedLoginLanguage", selectedLanguage);
            language.setIeft(selectedLanguage);
        }

        final String selectedOrganization = request.getParameter("organization");
        if (StringUtils.hasText(selectedOrganization) && session != null) {
            session.setAttribute("selectedOrganization", selectedOrganization);
        }

        final String redirect_uri = request.getParameter("redirect_uri");
        if (StringUtils.hasText(redirect_uri) && session != null) {
            session.setAttribute("redirect_uri", redirect_uri);
        }

        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (session != null && auth != null) {

            if (this.expireOldestSession && this.maximumSessions > 0) {
                // It the SessionRegistry doesn't find the sessionId, this is because the
                // session has already been
                // removed
                final String sessionId = session.getId();
                final SessionInformation si = this.sessionRegistry.getSessionInformation(sessionId);
                IMap<String, HttpSession> mapSessions = this.hazelcastInstance.getMap(HZ_MAP_NAME_FOR_CXP_SESSION);
                EntryView<String, HttpSession> hzSessionEntryView = mapSessions.getEntryView(sessionId);
                if (hzSessionEntryView == null && si == null) {
                    if (auth instanceof CxpAuthenticationToken) {
                        this.userSessionRepository.delete(((CxpAuthenticationToken) auth).getAccessToken());
                    }
                    LOGGER.info(String.format("The session for '%s' has been terminated as the same user logged in",
                                              auth.getName()));
                    SecurityContextHolder.clearContext();
                    if (!StringUtils.isEmpty(this.forwardProxy)) {
                        response.sendRedirect(
                                this.forwardProxy + request.getContextPath() + LoginFailureHandler.SESSION_LIMIT_REACHED);
                    } else {
                        response.sendRedirect(request.getContextPath() + LoginFailureHandler.SESSION_LIMIT_REACHED);
                    }
                    return;
                }
            }

            final long nowMillis = new Date().getTime();
            final Object sessionAttribute = session.getAttribute(KEY);
            if (sessionAttribute != null) {
                final long lastRefreshed = (long) sessionAttribute;
                LOGGER.debug("Session last refreshed = {}, now = {}", lastRefreshed, nowMillis);
                if (nowMillis > lastRefreshed + (this.maxSessionIdleTime * ProfileUtils.MILLIS_IN_SEC)) {
                    // Session expired
                    LOGGER.debug("Expiring Session and clearing Security Context");
                    session.invalidate();
                    SecurityContextHolder.clearContext();
                } else {
                    LOGGER.debug("Refreshing session if required.");
                    setSessionAttributeIfRequired(request, session, nowMillis);
                }
            } else {
                LOGGER.debug("No Session attribute found. Refreshing session if required.");
                setSessionAttributeIfRequired(request, session, nowMillis);
            }
        } else {
            if (session != null && session instanceof HttpSession) {
                LOGGER.debug("No authentication object found in the security context. Invalidating session {}", session.getId());
                // If the user has been logged out and the current Hazelcast session is still
                // valid, then the Hz session needs to be invalidated.
                final SessionInformation si = this.sessionRegistry.getSessionInformation(session.getId());
                if (si == null && request.getRequestURL() != null && !request.getRequestURL()
                        .toString().contains(J_SPRING_SECURITY_CHECK)) {
                    session.invalidate();
                }
            }
        }
        chain.doFilter(request, response);
        if (SecurityContextHolder.getContext() != null && SecurityContextHolder.getContext()
                .getAuthentication() instanceof CxpAuthenticationToken && language.getIeft() != null && !SecurityContextHolder.getContext()
                .getAuthentication().getName().equals("internal_admin")) {
            String userName = SecurityContextHolder.getContext().getAuthentication().getName();
            Map<String, String> userPreferences = new HashMap<>();
            ReadLanguageResponse languageModel = languageService.getLanguage(language.getIeft());
            userPreferences.put("language", languageModel.getLanguage().getNativeName());
            Set<ConfigurationSettingData> settings = new HashSet<>();
            for (Map.Entry<String, String> userPreference : userPreferences.entrySet()) {
                settings.add(new ConfigurationSettingData(userPreference.getKey(), userPreference.getValue()));
            }
            try {
                this.preferencesUserSettingsService.setPreferencesForUser(
                        new SetSettingsForUserRequest(userName, null, settings));
                language.setIeft(null);
            } catch (BusinessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Sets the attribute in the provided {@link HttpSession} if the {@link #noRefreshHeaderName} Http Header is not
     * found in the provided {@link HttpServletRequest}.
     *
     * @param request        The {@link HttpServletRequest} to check.
     * @param session        The {@link HttpSession} to store the attribute in if required.
     * @param attributeValue The value to store in the session if required.
     */
    private void setSessionAttributeIfRequired(final HttpServletRequest request, final HttpSession session,
            final long attributeValue) {

        if (!StringUtils.hasText(request.getHeader(this.noRefreshHeaderName))) {
            // 'No Refresh' header not present so update last request date/time
            LOGGER.debug("No matching header found. Session refreshed for SESSIONID {}",
                         session.getId());
            session.setAttribute(KEY, attributeValue);
        }
    }
}
