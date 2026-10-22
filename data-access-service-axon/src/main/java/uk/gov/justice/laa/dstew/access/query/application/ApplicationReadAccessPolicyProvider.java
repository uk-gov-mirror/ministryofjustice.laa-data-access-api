package uk.gov.justice.laa.dstew.access.query.application;

import static uk.gov.justice.laa.dstew.access.security.SecurityHelper.ACCESS_AS_PROVIDER;
import static uk.gov.justice.laa.dstew.access.security.SecurityHelper.ACCESS_AS_USER;
import static uk.gov.justice.laa.dstew.access.security.SecurityHelper.DECIDE_AUTO_GRANT_ROLE;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import uk.gov.justice.laa.dstew.access.query.utils.security.OfficeCodeReadAccessScope;
import uk.gov.justice.laa.dstew.access.query.utils.security.ReadAccessScope;
import uk.gov.justice.laa.dstew.access.query.utils.security.UnrestrictedReadAccessScope;
import uk.gov.justice.laa.dstew.access.security.SecurityHelper;

/** Resolves Application row visibility from authenticated request data. */
@Component
public class ApplicationReadAccessPolicyProvider {

  /** Resolves row visibility from delegated scopes or the dedicated service role. */
  public ReadAccessScope resolve() {
    Set<String> scopes = SecurityHelper.getScopes();
    if (scopes.contains(ACCESS_AS_PROVIDER)) {
      Set<String> officeCodes = new LinkedHashSet<>();
      SecurityHelper.getLaaAccounts().stream()
          .filter(account -> account != null && !account.isBlank())
          .map(String::trim)
          .forEach(officeCodes::add);
      return new OfficeCodeReadAccessScope(officeCodes);
    }
    if (scopes.contains(ACCESS_AS_USER)) {
      return new UnrestrictedReadAccessScope();
    }
    if (scopes.isEmpty() && SecurityHelper.getEntraAppRoles().contains(DECIDE_AUTO_GRANT_ROLE)) {
      return new UnrestrictedReadAccessScope();
    }
    throw new AccessDeniedException("Token does not contain a supported application access scope");
  }
}
