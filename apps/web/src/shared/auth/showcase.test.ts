import { csrfToken, showcaseHeaders, takeSessionExpired } from './showcase';

describe('showcase session', () => {
  it('reads the CSRF token from the cookie', () => {
    expect(csrfToken('a=1; XSRF-TOKEN=abc%3D%3D; b=2')).toBe('abc==');
    expect(csrfToken('a=1')).toBeNull();
    expect(csrfToken('')).toBeNull();
  });

  it('sends the persona on every call and the CSRF token on unsafe ones only', () => {
    expect(showcaseHeaders('GET', 'middle', 'tok')).toEqual({ 'X-Persona': 'middle' });
    expect(showcaseHeaders('post', 'middle', 'tok')).toEqual({ 'X-Persona': 'middle', 'X-XSRF-TOKEN': 'tok' });
    expect(showcaseHeaders('DELETE', 'admin', null)).toEqual({ 'X-Persona': 'admin' });
  });

  it('reports an expired session once', () => {
    window.sessionStorage.setItem('etrm.sessionExpired', '1');
    expect(takeSessionExpired()).toBe(true);
    expect(takeSessionExpired()).toBe(false);
  });
});
