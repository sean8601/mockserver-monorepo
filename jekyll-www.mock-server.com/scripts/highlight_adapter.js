/*
 * Adapter so highlight.js works with the site's existing markup
 * (<pre class="prettyprint lang-X code"><code class="code">...</code></pre>)
 * without rewriting the ~1900 code blocks.
 *
 * - reads the language from the `lang-X` class on the <pre>
 * - maps a few aliases to highlight.js language names
 * - SKIPS any block that already contains manual <span> highlighting
 *   (e.g. hand-marked XML with <span class="element">, numeric_literal, etc.)
 *   so those are left exactly as authored
 * - falls back to auto-detection when there is no lang-X class
 */
(function () {
  var ALIASES = {
    js: 'javascript',
    sh: 'bash',
    shell: 'bash',
    text: 'plaintext',
    txt: 'plaintext',
    toml: 'ini',
    'c#': 'csharp'
  };

  function highlightAll() {
    if (!window.hljs) return;
    try { hljs.configure({ ignoreUnescapedHTML: true }); } catch (e) {}

    var blocks = document.querySelectorAll('pre.prettyprint code, pre[class*="lang-"] code');
    Array.prototype.forEach.call(blocks, function (code) {
      if (code.dataset.hlDone) return;
      // leave hand-highlighted blocks (manual <span>s) untouched
      if (code.querySelector('span')) { code.dataset.hlDone = '1'; return; }

      var pre = code.closest('pre');
      if (!pre) return;
      var m = pre.className.match(/(?:^|\s)lang-([a-z0-9#+]+)/i);
      var lang = m ? m[1].toLowerCase() : null;
      if (lang && ALIASES[lang]) lang = ALIASES[lang];

      if (lang && hljs.getLanguage(lang)) {
        code.classList.add('language-' + lang);
      }
      // (no language class -> highlight.js auto-detects)
      try { hljs.highlightElement(code); } catch (e) {}
      code.dataset.hlDone = '1';
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', highlightAll);
  } else {
    highlightAll();
  }
})();
