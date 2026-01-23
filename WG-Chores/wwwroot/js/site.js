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
};
