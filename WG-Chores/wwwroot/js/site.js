window.wgChores = {
  clickElementById: function (id) {
    try {
      var el = document.getElementById(id);
      if (!el) return false;
      el.focus();
      // Some browsers open the date picker on click, others on focus+click
      var ev = new MouseEvent('click', { bubbles: true, cancelable: true, view: window });
      el.dispatchEvent(ev);
      return true;
    } catch (e) {
      console.error(e);
      return false;
    }
  }
  ,
  // Cookie helper functions
  getCookie: function (name) {
    try {
      var match = document.cookie.match(new RegExp('(?:^|; )' + name.replace(/([.$?*|{}()\[\]\\/+^])/g, '\\$1') + '=([^;]*)'));
      return match ? decodeURIComponent(match[1]) : null;
    } catch (e) {
      console.error(e);
      return null;
    }
  },
  setCookie: function (name, value, days) {
    try {
      var encoded = encodeURIComponent(value === null || value === undefined ? '' : value);
      var cookie = name + '=' + encoded + '; path=/';
      if (typeof days === 'number') {
        var maxAge = Math.floor(days * 24 * 60 * 60);
        cookie += '; max-age=' + maxAge;
      } else {
        // default to 1 year
        cookie += '; max-age=' + (365 * 24 * 60 * 60);
      }
      if (location && location.protocol === 'https:') cookie += '; Secure';
      // Don't set HttpOnly here; JS cannot create HttpOnly cookies.
      document.cookie = cookie;
      return true;
    } catch (e) {
      console.error(e);
      return false;
    }
  },
  deleteCookie: function (name) {
    try {
      document.cookie = name + '=; path=/; max-age=0';
      return true;
    } catch (e) {
      console.error(e);
      return false;
    }
  }
};
