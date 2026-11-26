// Hides cookie / consent pop-ups. Where a banner has a plain "reject / necessary only" button it is clicked (that refuses
// tracking cookies rather than agreeing to them); what is left is hidden, and a page the banner froze is scrolling again.
(function () {
  if (window.__mbCookieHide) return;
  window.__mbCookieHide = true;
  if (window.top !== window) return;

  var SEL = [
    '#onetrust-consent-sdk', '#onetrust-banner-sdk', '.onetrust-pc-dark-filter',
    '#CybotCookiebotDialog', '#CybotCookiebotDialogBodyUnderlay',
    '#didomi-host', '.didomi-popup-backdrop', '#didomi-notice',
    '.qc-cmp2-container', '#qc-cmp2-container',
    '#truste-consent-track', '#truste-consent-content', '.truste_overlay', '.truste_box_overlay',
    '#usercentrics-root', '#usercentrics-cmp-ui',
    '.osano-cm-window', '.osano-cm-dialog',
    '.cky-consent-container', '.cky-overlay', '#cookie-law-info-bar', '#cookie-law-info-again', '.cli-modal-backdrop',
    '#cmplz-cookiebanner-container', '.cmplz-cookiebanner',
    '#BorlabsCookieBox', '.BorlabsCookie',
    '#termly-code-snippet-support', '[id^="termly-"]',
    '#iubenda-cs-banner', '.iubenda-cs-container',
    '.sp_message_container', '[id^="sp_message_container_"]', '.sp_veil',
    '.klaro', '.cookie-consent', '.cc-window', '.cc-banner', '#cookieconsent', '.cookie-notice-container', '#cookie-notice',
    '#cookiebanner', '#cookie-banner', '#cookieBanner', '.cookie-banner', '.cookie-bar', '#cookie-bar', '#cookiebar',
    '#gdpr-cookie-notice', '.gdpr-cookie-notice', '#gdpr-banner', '.gdpr-banner', '#gdpr-consent', '.gdpr-consent',
    '#consent-banner', '.consent-banner', '#cookie-consent', '#cookie_consent', '#cookies-notice', '.cookies-notice',
    '#moove_gdpr_cookie_info_bar', '.moove-gdpr-cookie-info-bar', '#cookie-consent-banner', '.cookie-consent-banner',
    '#cookiescript_injected', '#cookiescript_injected_wrapper', '#hs-eu-cookie-confirmation', '#cookieChoiceInfo',
    '#sd-cmp', '.fc-consent-root', '.fc-dialog-overlay', '[aria-label="Cookie banner"]', '[aria-label="Cookie consent"]'
  ];
  var css = SEL.join(',') + '{display:none!important;visibility:hidden!important;pointer-events:none!important}' +
    'html.mb-cookie-unlock,html.mb-cookie-unlock body{overflow:auto!important;position:static!important;height:auto!important}';
  var st = document.createElement('style');
  st.textContent = css;
  (document.head || document.documentElement).appendChild(st);

  var REJECT = [
    '#onetrust-reject-all-handler', '.ot-pc-refuse-all-handler', '#onetrust-pc-btn-handler.cookie-setting-link-none',
    '#CybotCookiebotDialogBodyButtonDecline', '#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowallSelection',
    '#didomi-notice-disagree-button', '.didomi-continue-without-agreeing',
    '.qc-cmp2-summary-buttons button[mode="secondary"]',
    '.cky-btn-reject', '.cmplz-deny', '.cmplz-btn.cmplz-deny', '#cookie_action_close_header_reject', '.osano-cm-denyAll',
    '.iubenda-cs-reject-btn', '#iubFooterBtn', '.sp_choice_type_REJECT_ALL', 'button[title="Reject All"]',
    '#truste-consent-required', '.fc-cta-do-not-consent', '#BorlabsCookieBox ._brlbs-refuse-btn a', '.cc-deny', '.cc-btn.cc-deny'
  ];
  var WORDS = /^\s*(reject all|reject|decline all|decline|refuse all|refuse|deny all|deny|only (necessary|essential)( cookies)?|necessary( cookies)? only|essential( cookies)? only|use necessary cookies only|continue without accepting|disagree|no thanks|no, thanks)\s*$/i;

  function visible(e) { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  function tryReject() {
    var i, el;
    for (i = 0; i < REJECT.length; i++) { try { el = document.querySelector(REJECT[i]); } catch (e) { el = null; } if (el && visible(el)) { el.click(); return true; } }
    var roots = document.querySelectorAll(SEL.join(','));
    for (i = 0; i < roots.length; i++) {
      var bs = roots[i].querySelectorAll('button,a[role=button],[role=button]');
      for (var j = 0; j < bs.length; j++) { var t = (bs[j].innerText || bs[j].textContent || bs[j].getAttribute('aria-label') || '').trim(); if (t.length < 40 && WORDS.test(t)) { bs[j].click(); return true; } }
    }
    return false;
  }
  function unlock() {
    var de = document.documentElement, b = document.body;
    if (!b) return;
    var cs = getComputedStyle(b), ch = getComputedStyle(de);
    if (cs.overflow === 'hidden' || ch.overflow === 'hidden' || cs.position === 'fixed') {
      // only when one of our hidden banners is what froze it
      if (document.querySelector(SEL.join(','))) de.classList.add('mb-cookie-unlock');
    }
  }

  var tries = 0, clicked = false;
  function tick() {
    if (!clicked) clicked = tryReject();
    unlock();
    if (++tries > 40) { clearInterval(iv); }
  }
  var iv = setInterval(tick, 400);
  document.addEventListener('DOMContentLoaded', tick);
})();
