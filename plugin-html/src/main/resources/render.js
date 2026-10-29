/*!
 * render.js — DSH plugin-html 离线渲染器
 * Markdown + LaTeX + 白名单 HTML → 可直接塞进 #dsh-root 的内容。
 * 零依赖、零网络。宿主通过 loadDataWithBaseURL("about:blank", ...) 内联执行。
 *
 * @version 2.0.0
 */
(function (global) {
  'use strict';

  var VERSION = '2.0.0';

  /* ------------------------------------------------------------ lexicon */

  var GREEK = {
    alpha:'\u03b1', beta:'\u03b2', gamma:'\u03b3', delta:'\u03b4', epsilon:'\u03f5',
    varepsilon:'\u03b5', zeta:'\u03b6', eta:'\u03b7', theta:'\u03b8', vartheta:'\u03d1',
    iota:'\u03b9', kappa:'\u03ba', varkappa:'\u03f0', lambda:'\u03bb', mu:'\u03bc',
    nu:'\u03bd', xi:'\u03be', omicron:'\u03bf', pi:'\u03c0', varpi:'\u03d6',
    rho:'\u03c1', varrho:'\u03f1', sigma:'\u03c3', varsigma:'\u03c2', tau:'\u03c4',
    upsilon:'\u03c5', phi:'\u03d5', varphi:'\u03c6', chi:'\u03c7', psi:'\u03c8', omega:'\u03c9',
    Gamma:'\u0393', Delta:'\u0394', Theta:'\u0398', Lambda:'\u039b', Xi:'\u039e',
    Pi:'\u03a0', Sigma:'\u03a3', Upsilon:'\u03a5', Phi:'\u03a6', Psi:'\u03a8', Omega:'\u03a9'
  };

  var SYMBOLS = {
    times:'\u00d7', cdot:'\u22c5', div:'\u00f7', pm:'\u00b1', mp:'\u2213',
    ast:'\u2217', star:'\u22c6', circ:'\u2218', bullet:'\u2022',
    le:'\u2264', leq:'\u2264', ge:'\u2265', geq:'\u2265', ne:'\u2260', neq:'\u2260',
    approx:'\u2248', equiv:'\u2261', cong:'\u2245', sim:'\u223c', simeq:'\u2243',
    propto:'\u221d', asymp:'\u224d', ll:'\u226a', gg:'\u226b',
    to:'\u2192', rightarrow:'\u2192', leftarrow:'\u2190', gets:'\u2190',
    Rightarrow:'\u21d2', Leftarrow:'\u21d0', leftrightarrow:'\u2194', Leftrightarrow:'\u21d4',
    uparrow:'\u2191', downarrow:'\u2193', Uparrow:'\u21d1', Downarrow:'\u21d3',
    mapsto:'\u21a6', hookrightarrow:'\u21aa', implies:'\u21d2', iff:'\u21d4',
    infty:'\u221e', partial:'\u2202', nabla:'\u2207', ell:'\u2113', hbar:'\u210f',
    forall:'\u2200', exists:'\u2203', nexists:'\u2204', neg:'\u00ac', lnot:'\u00ac',
    in:'\u2208', notin:'\u2209', ni:'\u220b', subset:'\u2282', subseteq:'\u2286',
    supset:'\u2283', supseteq:'\u2287', subsetneq:'\u228a', cup:'\u222a', cap:'\u2229',
    setminus:'\u2216', emptyset:'\u2205', varnothing:'\u2205', complement:'\u2201',
    wedge:'\u2227', vee:'\u2228', land:'\u2227', lor:'\u2228', oplus:'\u2295',
    otimes:'\u2297', odot:'\u2299', perp:'\u22a5', parallel:'\u2225', angle:'\u2220',
    triangle:'\u25b3', square:'\u25a1', diamond:'\u22c4', top:'\u22a4', bot:'\u22a5',
    sum:'\u2211', prod:'\u220f', coprod:'\u2210', int:'\u222b', iint:'\u222c',
    iiint:'\u222d', oint:'\u222e', bigcup:'\u22c3', bigcap:'\u22c2',
    ldots:'\u2026', cdots:'\u22ef', vdots:'\u22ee', ddots:'\u22f1', dots:'\u2026',
    degree:'\u00b0', prime:'\u2032', dprime:'\u2033', therefore:'\u2234', because:'\u2235',
    aleph:'\u2135', Re:'\u211c', Im:'\u2111', wp:'\u2118', surd:'\u221a',
    quad:'\u2003', qquad:'\u2003\u2003', enspace:'\u2002', thinspace:'\u2009',
    textstyle:'', displaystyle:'', limits:'', nolimits:'', nonumber:'', notag:'',
    left:'', right:'', big:'', Big:'', bigg:'', Bigg:'', bigl:'', bigr:''
  };

  var FUNCS = {
    lim:1, limsup:1, liminf:1, log:1, ln:1, lg:1, exp:1, sin:1, cos:1, tan:1,
    cot:1, sec:1, csc:1, arcsin:1, arccos:1, arctan:1, sinh:1, cosh:1, tanh:1,
    max:1, min:1, sup:1, inf:1, det:1, gcd:1, deg:1, dim:1, ker:1, hom:1, arg:1,
    Pr:1, mod:1, bmod:1
  };

  var BIG_OPS = {
    sum:1, prod:1, coprod:1, int:1, iint:1, iiint:1, oint:1,
    lim:1, limsup:1, liminf:1, max:1, min:1, sup:1, inf:1,
    bigcup:1, bigcap:1
  };

  /* ------------------------------------------------------------ escape */

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  /* ------------------------------------------------------------ LaTeX */

  function texHtml(src) {
    return parseTex(String(src == null ? '' : src), 0)[0];
  }

  function skipSpace(s, i) {
    while (i < s.length && (s.charAt(i) === ' ' || s.charAt(i) === '\n')) i++;
    return i;
  }

  /* 读一个参数：{group} / \command / 单字符。返回 [html, next]。 */
  function readArg(s, i) {
    i = skipSpace(s, i);
    if (i >= s.length) return ['', i];
    var c = s.charAt(i);
    if (c === '{') {
      var g = parseTex(s, i + 1);
      return [g[0], g[1] + 1];
    }
    if (c === '\\') return command(s, i + 1);
    if (c === '}') return ['', i];
    return [esc(c), i + 1];
  }

  /* 读原始文本参数（环境名、\color{red}）。 */
  function readRaw(s, i) {
    i = skipSpace(s, i);
    if (s.charAt(i) !== '{') return ['', i];
    var depth = 0, j = i;
    for (; j < s.length; j++) {
      var c = s.charAt(j);
      if (c === '{') depth++;
      else if (c === '}') { depth--; if (depth === 0) return [s.slice(i + 1, j), j + 1]; }
    }
    return [s.slice(i + 1), s.length];
  }

  /* 一组 (sub, sup)，顺序不限，最多各一次。 */
  function readScripts(s, i) {
    var sub = '', sup = '', guard = 0;
    while (guard++ < 6) {
      var t = skipSpace(s, i);
      var c = s.charAt(t);
      if (c === '^') { var u = readArg(s, t + 1); sup = u[0]; i = u[1]; }
      else if (c === '_') { var d = readArg(s, t + 1); sub = d[0]; i = d[1]; }
      else if (c === "'") { sup += '\u2032'; i = t + 1; }
      else break;
    }
    return [sub, sup, i];
  }

  function parseTex(s, i) {
    var out = '';
    while (i < s.length) {
      var c = s.charAt(i);
      if (c === '}') return [out, i];
      if (c === '\\') {
        var r = command(s, i + 1);
        out += r[0];
        i = r[1];
        continue;
      }
      if (c === '{') {
        var g = parseTex(s, i + 1);
        out += g[0];
        i = g[1] + 1;
        continue;
      }
      if (c === '^') {
        var up = readArg(s, i + 1);
        out += '<sup>' + up[0] + '</sup>';
        i = up[1];
        continue;
      }
      if (c === '_') {
        var dn = readArg(s, i + 1);
        out += '<sub>' + dn[0] + '</sub>';
        i = dn[1];
        continue;
      }
      if (c === '&') { i++; continue; }
      if (c === '~') { out += '\u00a0'; i++; continue; }
      if (c === '\n') { out += ' '; i++; continue; }
      out += esc(c);
      i++;
    }
    return [out, i];
  }

  function frac(num, den, cls) {
    return '<span class="tex-frac ' + (cls || '') + '"><span class="tex-num">' + num +
      '</span><span class="tex-den">' + den + '</span></span>';
  }

  function matrix(body, env) {
    var rows = body.split(/\\\\/);
    var wrap = '', cls = 'tex-matrix';
    if (env === 'pmatrix') wrap = ['(', ')'];
    else if (env === 'bmatrix') wrap = ['[', ']'];
    else if (env === 'vmatrix') wrap = ['|', '|'];
    else if (env === 'Bmatrix') wrap = ['{', '}'];
    else if (env === 'Vmatrix') wrap = ['\u2016', '\u2016'];
    else if (env === 'cases') { wrap = ['{', '']; cls += ' tex-cases'; }
    var html = '<span class="tex-matrix-wrap"><span class="tex-matrix-br">' + (wrap[0] || '') +
      '</span><table class="' + cls + '"><tbody>';
    for (var r = 0; r < rows.length; r++) {
      if (!rows[r].replace(/\s/g, '')) continue;
      var cells = rows[r].split('&');
      html += '<tr>';
      for (var q = 0; q < cells.length; q++) {
        html += '<td>' + texHtml(cells[q]) + '</td>';
      }
      html += '</tr>';
    }
    html += '</tbody></table><span class="tex-matrix-br">' + (wrap[1] || '') + '</span></span>';
    return html;
  }

  function alignedBlock(body) {
    var rows = body.split(/\\\\/);
    var html = '<table class="tex-align"><tbody>';
    for (var r = 0; r < rows.length; r++) {
      if (!rows[r].replace(/\s/g, '')) continue;
      var cells = rows[r].split('&');
      html += '<tr>';
      for (var q = 0; q < cells.length; q++) {
        html += '<td class="ta-' + (q % 2 === 0 ? 'r' : 'l') + '">' + texHtml(cells[q]) + '</td>';
      }
      html += '</tr>';
    }
    return html + '</tbody></table>';
  }

  function command(s, i) {
    var m = /^[a-zA-Z]+/.exec(s.slice(i));
    var name, next;
    if (m) { name = m[0]; next = i + name.length; } else { name = s.charAt(i); next = i + 1; }

    if (name === 'frac' || name === 'dfrac' || name === 'tfrac' || name === 'cfrac') {
      var na = readArg(s, next);
      var da = readArg(s, na[1]);
      return [frac(na[0], da[0], name === 'dfrac' ? 'tex-dfrac' : ''), da[1]];
    }

    if (name === 'binom' || name === 'dbinom' || name === 'tbinom') {
      var bn = readArg(s, next);
      var bd = readArg(s, bn[1]);
      return [frac(bn[0], bd[0], 'tex-bin'), bd[1]];
    }

    if (name === 'sqrt') {
      var k = next, idx = '';
      if (s.charAt(skipSpace(s, k)) === '[') {
        k = skipSpace(s, k);
        var rb = s.indexOf(']', k);
        if (rb > k) { idx = s.slice(k + 1, rb); k = rb + 1; }
      }
      var ra = readArg(s, k);
      var root = '<span class="tex-sqrt">';
      if (idx) root += '<span class="tex-sqrt-idx">' + texHtml(idx) + '</span>';
      root += '<span class="tex-radical">\u221a</span><span class="tex-radicand">' + ra[0] + '</span></span>';
      return [root, ra[1]];
    }

    if (BIG_OPS[name]) {
      var sym = SYMBOLS[name] || name;
      var sc = readScripts(s, next);
      var op = '<span class="tex-op"><span class="tex-op-sup">' + sc[1] + '</span>' +
        '<span class="tex-op-sym">' + sym + '</span>' +
        '<span class="tex-op-sub">' + sc[0] + '</span></span>';
      return [op, sc[2]];
    }

    if (name === 'left' || name === 'right' || name === 'middle') {
      next = skipSpace(s, next);
      var delim = s.charAt(next);
      if (delim === '\\') {
        var dm = /^[a-zA-Z]+/.exec(s.slice(next + 1));
        if (dm) {
          var dn2 = dm[0];
          var map = { lbrace:'{', rbrace:'}', langle:'\u27e8', rangle:'\u27e9',
            lvert:'|', rvert:'|', lVert:'\u2016', rVert:'\u2016', vert:'|', Vert:'\u2016',
            lfloor:'\u230a', rfloor:'\u230b', lceil:'\u2308', rceil:'\u2309' };
          var dh = map[dn2] || SYMBOLS[dn2] || dn2;
          return ['<span class="tex-delim">' + dh + '</span>', next + 1 + dn2.length];
        }
        return ['<span class="tex-delim">' + esc(delim) + '</span>', next + 1];
      }
      return ['<span class="tex-delim">' + (delim === '.' ? '' : esc(delim)) + '</span>', next + 1];
    }

    if (name === 'begin') {
      var env = readRaw(s, next);
      var envName = env[0];
      if (/^(matrix|pmatrix|bmatrix|Bmatrix|vmatrix|Vmatrix|cases|array|smallmatrix)$/.test(envName)) {
        var endTag = '\\end{' + envName + '}';
        var close = s.indexOf(endTag, env[1]);
        if (close < 0) close = s.length;
        var inner = s.slice(env[1], close);
        if (envName === 'array') {
          var colSpec = readRaw(inner, 0);
          inner = inner.slice(colSpec[1]);
        }
        return [matrix(inner, envName), Math.min(s.length, close + endTag.length)];
      }
      if (/^(aligned|align|align\*|alignedat|gathered|gather|gather\*|split|eqnarray)$/.test(envName)) {
        var endTag2 = '\\end{' + envName + '}';
        var close2 = s.indexOf(endTag2, env[1]);
        if (close2 < 0) close2 = s.length;
        return [alignedBlock(s.slice(env[1], close2)), Math.min(s.length, close2 + endTag2.length)];
      }
      return ['', env[1]];
    }

    if (name === 'end') { var e2 = readRaw(s, next); return ['', e2[1]]; }

    if (name === 'text' || name === 'textrm' || name === 'textnormal' || name === 'mbox' ||
        name === 'textbf' || name === 'textit' || name === 'textsf' || name === 'texttt') {
      var ta = readArg(s, next);
      return ['<span class="tex-' + name + '">' + ta[0] + '</span>', ta[1]];
    }

    if (name === 'mathrm' || name === 'mathbf' || name === 'mathit' || name === 'mathsf' ||
        name === 'mathtt' || name === 'mathbb' || name === 'mathcal' || name === 'mathfrak' ||
        name === 'boldsymbol' || name === 'bm') {
      var ma = readArg(s, next);
      var mcls = name === 'bm' ? 'boldsymbol' : name;
      return ['<span class="tex-' + mcls + '">' + ma[0] + '</span>', ma[1]];
    }

    if (name === 'operatorname') {
      var oa = readArg(s, next);
      return ['<span class="tex-operatorname">' + oa[0] + '</span>', oa[1]];
    }

    if (name === 'vec' || name === 'hat' || name === 'bar' || name === 'tilde' ||
        name === 'dot' || name === 'ddot' || name === 'widehat' || name === 'widetilde' ||
        name === 'overline' || name === 'underline' || name === 'overrightarrow' ||
        name === 'overleftarrow' || name === 'check' || name === 'breve' ||
        name === 'acute' || name === 'grave') {
      var aa = readArg(s, next);
      if (name === 'underline') return ['<span class="tex-underline">' + aa[0] + '</span>', aa[1]];
      var mark = { vec:'\u20d7', hat:'\u0302', widehat:'\u0302', bar:'\u0304',
        overline:'\u0304', tilde:'\u0303', widetilde:'\u0303', dot:'\u0307',
        ddot:'\u0308', check:'\u030c', breve:'\u0306', acute:'\u0301',
        grave:'\u0300', overrightarrow:'\u20d7', overleftarrow:'\u20d6' }[name] || '';
      return ['<span class="tex-' + name + '">' + aa[0] +
        '<span class="tex-mark">' + mark + '</span></span>', aa[1]];
    }

    if (name === 'color' || name === 'colorbox') {
      var col = readRaw(s, next);
      var ca = readArg(s, col[1]);
      return ['<span style="color:' + esc(col[0]) + '">' + ca[0] + '</span>', ca[1]];
    }

    if (name === '\\') return ['<br/>', next];
    if (name === '{') return ['{', next];
    if (name === '}') return ['}', next];
    if (name === '%') return ['%', next];
    if (name === '&') return ['&amp;', next];
    if (name === '_') return ['_', next];
    if (name === '#') return ['#', next];
    if (name === ',') return ['\u2009', next];
    if (name === ':') return ['\u2005', next];
    if (name === ';') return ['\u2004', next];
    if (name === '!') return ['', next];
    if (name === ' ') return [' ', next];

    if (FUNCS[name]) return ['<span class="tex-func">' + name + '</span>', next];
    if (GREEK[name]) return [GREEK[name], next];
    if (SYMBOLS[name] !== undefined) return [SYMBOLS[name], next];

    return [esc(name), next];
  }
  /* ------------------------------------------------------------ inline */

  var SAFE_URL = /^(https?:\/\/|mailto:|tel:|#|\/)/i;

  function attr(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function linkHtml(href, label, cls) {
    var h = String(href || '').trim();
    if (!SAFE_URL.test(h)) h = '#';
    var a = '<a href="' + attr(h) + '"';
    if (cls) a += ' class="' + cls + '"';
    return a + ' rel="noreferrer noopener">' + label + '</a>';
  }

  /* 行内渲染：数学 / 代码 / 链接 先抽出占位，避免被强调规则污染，最后回填。 */
  function inlineMd(src) {
    var s = String(src == null ? '' : src);
    var slots = [];
    function hold(html) { slots.push(html); return '\u0000' + (slots.length - 1) + '\u0000'; }

    var out = '';
    var i = 0;
    while (i < s.length) {
      var c = s.charAt(i);

      if (c === '\\' && i + 1 < s.length) {
        var nx = s.charAt(i + 1);
        if (nx === '(') {
          var q = s.indexOf('\\)', i + 2);
          if (q > i) {
            out += hold('<span class="math-inline">' + texHtml(s.slice(i + 2, q)) + '</span>');
            i = q + 2;
            continue;
          }
        }
        if ('\\`*_{}[]()#+-.!|~$'.indexOf(nx) >= 0) { out += esc(nx); i += 2; continue; }
      }

      if (c === '$') {
        var dc = s.indexOf('$', i + 1);
        if (dc > i + 1) {
          out += hold('<span class="math-inline">' + texHtml(s.slice(i + 1, dc)) + '</span>');
          i = dc + 1;
          continue;
        }
      }

      if (c === '`') {
        var run = 1;
        while (s.charAt(i + run) === '`') run++;
        var fence = '';
        for (var f = 0; f < run; f++) fence += '`';
        var cc = s.indexOf(fence, i + run);
        if (cc > i) {
          var code = s.slice(i + run, cc);
          if (code.charAt(0) === ' ' && code.charAt(code.length - 1) === ' ') code = code.slice(1, -1);
          out += hold('<code class="md-code-inline">' + esc(code) + '</code>');
          i = cc + run;
          continue;
        }
      }

      if (c === '!' && s.charAt(i + 1) === '[') {
        var im = /^!\[([^\]]*)\]\(\s*([^)\s]*)[^)]*\)/.exec(s.slice(i));
        if (im) {
          out += hold('<span class="md-img">[图] ' + esc(im[1] || im[2] || 'image') + '</span>');
          i += im[0].length;
          continue;
        }
      }

      if (c === '[') {
        var lm = /^\[([^\]]*)\]\(\s*([^)\s]*)[^)]*\)/.exec(s.slice(i));
        if (lm) {
          out += hold(linkHtml(lm[2], inlineMd(lm[1])));
          i += lm[0].length;
          continue;
        }
        var rm = /^\[([^\]]+)\]\[([^\]]*)\]/.exec(s.slice(i));
        if (rm) { out += esc(rm[1]); i += rm[0].length; continue; }
      }

      if (c === '<') {
        var am = /^<((?:https?:\/\/|mailto:)[^>\s]+)>/.exec(s.slice(i));
        if (am) {
          out += hold(linkHtml(am[1], esc(am[1])));
          i += am[0].length;
          continue;
        }
        out += '&lt;';
        i++;
        continue;
      }

      if (c === '&') { out += '&amp;'; i++; continue; }
      if (c === '>') { out += '&gt;'; i++; continue; }

      if (c === '*' || c === '_') {
        var dbl = s.substr(i, 2) === c + c;
        if (dbl) {
          var b2 = s.indexOf(c + c, i + 2);
          if (b2 > i + 1) {
            var strong = s.slice(i + 2, b2);
            if (strong.length > 1 && strong.charAt(0) === c && strong.charAt(strong.length - 1) === c) {
              out += '<strong><em>' + inlineMd(strong.slice(1, -1)) + '</em></strong>';
            } else {
              out += '<strong>' + inlineMd(strong) + '</strong>';
            }
            i = b2 + 2;
            continue;
          }
        }
        var b1 = s.indexOf(c, i + 1);
        if (b1 > i + 1 && c !== '_') {
          out += '<em>' + inlineMd(s.slice(i + 1, b1)) + '</em>';
          i = b1 + 1;
          continue;
        }
      }

      if (c === '~' && s.charAt(i + 1) === '~') {
        var st = s.indexOf('~~', i + 2);
        if (st > i + 1) {
          out += '<del>' + inlineMd(s.slice(i + 2, st)) + '</del>';
          i = st + 2;
          continue;
        }
      }

      if (c === '=' && s.charAt(i + 1) === '=') {
        var mk = s.indexOf('==', i + 2);
        if (mk > i + 1) {
          out += '<mark>' + inlineMd(s.slice(i + 2, mk)) + '</mark>';
          i = mk + 2;
          continue;
        }
      }

      out += c;
      i++;
    }

    return out.replace(/\u0000(\d+)\u0000/g, function (_, n) { return slots[+n]; });
  }

  /* ------------------------------------------------------------ highlight */

  var KW = {
    js: 'const let var function return if else for while do switch case break continue new class extends super this typeof instanceof in of try catch finally throw async await yield delete void null undefined true false import export from default static get set'.split(' '),
    ts: 'const let var function return if else for while switch case break continue new class extends super this typeof instanceof try catch finally throw async await interface type enum implements readonly public private protected declare namespace as satisfies'.split(' '),
    kotlin: 'val var fun class object interface data sealed enum return if else for while when is as in out by init constructor override private public protected internal open abstract companion suspend inline lateinit null true false try catch finally throw import package this super'.split(' '),
    python: 'def class return if elif else for while import from as with try except finally raise lambda pass break continue None True False and or not is in global nonlocal assert yield async await print'.split(' '),
    java: 'public private protected class interface extends implements static final void int long double float boolean char String new return if else for while switch case break continue try catch finally throw import package this super null true false'.split(' '),
    c: 'int long short char float double void struct union enum typedef static const unsigned signed return if else for while switch case break continue goto sizeof extern register volatile inline'.split(' '),
    go: 'package import func var const type struct interface map chan go defer select range return if else for switch case break continue nil true false'.split(' '),
    rust: 'fn let mut const static struct enum trait impl for while loop match if else return pub use mod crate self super where unsafe async await move dyn ref'.split(' '),
    sql: 'select from where insert into values update set delete create table alter drop index join left right inner outer on group by order having limit offset as and or not null distinct union all'.split(' '),
    sh: 'if then else fi for do done while case esac function return export local readonly echo cd ls cp mv rm mkdir sudo apt npm yarn pnpm git docker'.split(' '),
    json: 'true false null'.split(' '),
    yaml: 'true false null'.split(' '),
    diff: ''.split(' ')
  };

  var ALIAS = {
    javascript: 'js', typescript: 'ts', jsx: 'js', tsx: 'ts', mjs: 'js', cjs: 'js',
    kt: 'kotlin', kts: 'kotlin', py: 'python', py3: 'python', rb: 'python',
    cpp: 'c', 'c++': 'c', h: 'c', hpp: 'c', cs: 'java', objc: 'c',
    golang: 'go', rs: 'rust', bash: 'sh', shell: 'sh', zsh: 'sh', console: 'sh',
    ps: 'sh', powershell: 'sh', ps1: 'sh', yml: 'yaml', patch: 'diff'
  };

  function kwSet(lang) {
    var l = String(lang || '').toLowerCase();
    l = ALIAS[l] || l;
    var list = KW[l] || [];
    var m = {};
    for (var i = 0; i < list.length; i++) m[list[i]] = 1;
    return { map: m, lang: l };
  }

  var RE_TOKEN = /(\/\/[^\n]*|#[^\n]*|\/\*[\s\S]*?\*\/|--[^\n]*)|('(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|`(?:\\.|[^`\\])*`)|(\b0[xX][0-9a-fA-F]+\b|\b\d+(?:\.\d+)?\b)|([A-Za-z_$][A-Za-z0-9_$]*)/g;

  function highlight(code, lang) {
    var ks = kwSet(lang);
    if (ks.lang === 'diff') {
      return code.split('\n').map(function (ln) {
        if (ln.charAt(0) === '+') return '<span class="hl-add">' + esc(ln) + '</span>';
        if (ln.charAt(0) === '-') return '<span class="hl-del">' + esc(ln) + '</span>';
        if (/^(diff|---|\+\+\+|@@|index )/.test(ln)) return '<span class="hl-meta">' + esc(ln) + '</span>';
        return esc(ln);
      }).join('\n');
    }
    var out = '';
    var last = 0;
    var m;
    RE_TOKEN.lastIndex = 0;
    while ((m = RE_TOKEN.exec(code)) !== null) {
      if (m.index > last) out += esc(code.slice(last, m.index));
      if (m[1]) out += '<span class="hl-com">' + esc(m[1]) + '</span>';
      else if (m[2]) out += '<span class="hl-str">' + esc(m[2]) + '</span>';
      else if (m[3]) out += '<span class="hl-num">' + esc(m[3]) + '</span>';
      else if (m[4]) {
        var w = m[4];
        if (ks.map[w]) out += '<span class="hl-kw">' + esc(w) + '</span>';
        else if (/^[A-Z]/.test(w) && ks.lang !== 'sh') out += '<span class="hl-type">' + esc(w) + '</span>';
        else if (code.charAt(RE_TOKEN.lastIndex) === '(') out += '<span class="hl-fn">' + esc(w) + '</span>';
        else out += esc(w);
      }
      last = RE_TOKEN.lastIndex;
      if (m[0] === '') RE_TOKEN.lastIndex++;
    }
    if (last < code.length) out += esc(code.slice(last));
    return out;
  }
  /* ------------------------------------------------------------ blocks */

  var RE_FENCE = /^\s*(```+|~~~+)\s*([A-Za-z0-9_+#.\-]*)\s*$/;
  var RE_HEAD = /^(#{1,6})\s+(.*)$/;
  var RE_HR = /^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/;
  var RE_UL = /^(\s*)([-*+])\s+(.*)$/;
  var RE_OL = /^(\s*)(\d{1,9})[.)]\s+(.*)$/;
  var RE_QUOTE = /^\s*>\s?(.*)$/;
  var RE_TABLE_SEP = /^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$/;

  function isBlank(s) { return !s || !s.replace(/\s/g, ''); }

  function lines(block) { return String(block == null ? '' : block).replace(/\r\n?/g, '\n').split('\n'); }

  function tableHtml(rows) {
    if (!rows.length) return '';
    function cells(line) {
      var t = line.trim();
      if (t.charAt(0) === '|') t = t.slice(1);
      if (t.charAt(t.length - 1) === '|') t = t.slice(0, -1);
      return t.split('|').map(function (x) { return x.trim(); });
    }
    var head = cells(rows[0]);
    var align = rows.length > 1 ? cells(rows[1]) : [];
    var body = rows.slice(rows.length > 1 ? 2 : 1);
    function alignOf(i) {
      var a = String(align[i] || '').trim();
      if (!a) return '';
      var l = a.charAt(0) === ':';
      var r = a.charAt(a.length - 1) === ':';
      if (l && r) return ' class="ta-c"';
      if (r) return ' class="ta-r"';
      if (l) return ' class="ta-l"';
      return '';
    }
    var h = '<div class="md-table-wrap"><table class="md-table"><thead><tr>';
    for (var i = 0; i < head.length; i++) h += '<th' + alignOf(i) + '>' + inlineMd(head[i]) + '</th>';
    h += '</tr></thead><tbody>';
    for (var r = 0; r < body.length; r++) {
      if (isBlank(body[r])) continue;
      var bc = cells(body[r]);
      h += '<tr>';
      for (var q = 0; q < head.length; q++) h += '<td' + alignOf(q) + '>' + inlineMd(bc[q] || '') + '</td>';
      h += '</tr>';
    }
    return h + '</tbody></table></div>';
  }

  /* 列表：按缩进递归，支持嵌套混排与 task list。 */
  function listHtml(src, ordered, baseIndent) {
    var ls = lines(src);
    var out = ordered ? '<ol>' : '<ul>';
    var cur = null;
    var curIndent = baseIndent || 0;

    function flush() {
      if (cur === null) return;
      var cls = cur.task === null ? '' : ' class="md-task' + (cur.task ? ' done' : '') + '"';
      out += '<li' + cls + '>';
      if (cur.task !== null) out += '<span class="md-check">' + (cur.task ? '\u2611' : '\u2610') + '</span>';
      out += inlineMd(cur.head);
      if (cur.nested.length) {
        var nOrdered = RE_OL.test(cur.nested[0]);
        out += listHtml(cur.nested.join('\n'), nOrdered, cur.nestedIndent);
      }
      out += '</li>';
      cur = null;
    }

    for (var i = 0; i < ls.length; i++) {
      var ln = ls[i];
      if (isBlank(ln)) continue;
      var m = ordered ? RE_OL.exec(ln) : RE_UL.exec(ln);
      var other = ordered ? RE_UL.exec(ln) : RE_OL.exec(ln);
      if (!m && other) {
        if (cur !== null) { cur.nested.push(ln); }
        continue;
      }
      if (m) {
        var ind = m[1].length;
        var text = m[3];
        var task = null;
        var tm = /^\[([ xX])\]\s*(.*)$/.exec(text);
        if (tm) { task = tm[1].toLowerCase() === 'x'; text = tm[2]; }
        if (cur !== null && ind > curIndent) { cur.nested.push(ln); continue; }
        flush();
        curIndent = ind;
        cur = { head: text, nested: [], nestedIndent: ind + 2, task: task };
        continue;
      }
      if (cur !== null && /^\s{2,}\S/.test(ln)) { cur.nested.push(ln); continue; }
      if (cur !== null) { cur.head += ' ' + ln.trim(); continue; }
    }
    flush();
    return out + (ordered ? '</ol>' : '</ul>');
  }

  function codeBlock(code, lang) {
    var l = String(lang || '').trim();
    var body;
    if (!l) body = highlight(code, 'sh');
    else if (l.toLowerCase() === 'text' || l.toLowerCase() === 'plain' || l.toLowerCase() === 'none') body = esc(code);
    else body = highlight(code, l);
    return '<div class="md-code"><div class="md-code-head"><span class="md-code-lang">' +
      esc(l || 'text') + '</span></div><pre><code>' + body + '</code></pre></div>';
  }

  function blockHtml(src) {
    var ls = lines(src);
    var out = '';
    var i = 0;
    while (i < ls.length) {
      var ln = ls[i];

      if (isBlank(ln)) { i++; continue; }

      var fm = RE_FENCE.exec(ln);
      if (fm) {
        var ch = fm[1].charAt(0);
        var lang = fm[2];
        var buf = [];
        i++;
        while (i < ls.length && !new RegExp('^\\s*' + (ch === '$' ? '\\$' : ch) + '{3,}\\s*$').test(ls[i])) {
          buf.push(ls[i]);
          i++;
        }
        if (i < ls.length) i++;
        out += codeBlock(buf.join('\n'), lang);
        continue;
      }

      var trimmed = ln.trim();
      if (trimmed === '$$' || trimmed === '\\[') {
        var closer = trimmed === '$$' ? '$$' : '\\]';
        var mb = [];
        i++;
        while (i < ls.length && ls[i].trim() !== closer) { mb.push(ls[i]); i++; }
        if (i < ls.length) i++;
        out += '<div class="math-block">' + texHtml(mb.join(' ')) + '</div>';
        continue;
      }
      if (/^\$\$.+\$\$$/.test(trimmed)) {
        out += '<div class="math-block">' + texHtml(trimmed.slice(2, -2)) + '</div>';
        i++;
        continue;
      }
      if (/^\\\[.+\\\]$/.test(trimmed)) {
        out += '<div class="math-block">' + texHtml(trimmed.slice(2, -2)) + '</div>';
        i++;
        continue;
      }

      if (/^\s*<(div|section|figure|table|details|p|pre|blockquote|ul|ol|h[1-6]|svg|style)\b/i.test(ln)) {
        var name = (/^\s*<([a-zA-Z0-9]+)/.exec(ln) || ['', 'div'])[1].toLowerCase();
        var hb = [];
        while (i < ls.length) {
          hb.push(ls[i]);
          var closed = new RegExp('</' + name + '>\\s*$', 'i').test(ls[i]);
          i++;
          if (closed) break;
        }
        out += hb.join('\n');
        continue;
      }

      var hm = RE_HEAD.exec(ln);
      if (hm) {
        var lvl = hm[1].length;
        out += '<h' + lvl + ' class="md-h md-h' + lvl + '">' + inlineMd(hm[2].trim()) + '</h' + lvl + '>';
        i++;
        continue;
      }

      if (i + 1 < ls.length && !RE_HR.test(ln) && /^\s*(=+|-{2,})\s*$/.test(ls[i + 1]) &&
          !RE_UL.test(ln) && !RE_OL.test(ln)) {
        var lv = /^\s*=+\s*$/.test(ls[i + 1]) ? 1 : 2;
        out += '<h' + lv + ' class="md-h md-h' + lv + '">' + inlineMd(ln.trim()) + '</h' + lv + '>';
        i += 2;
        continue;
      }

      if (/^\\begin\{/.test(trimmed)) {
        var mb2 = [ln];
        var depthB = (ln.match(/\\begin\{/g) || []).length - (ln.match(/\\end\{/g) || []).length;
        i++;
        while (i < ls.length && depthB > 0) {
          mb2.push(ls[i]);
          depthB += (ls[i].match(/\\begin\{/g) || []).length - (ls[i].match(/\\end\{/g) || []).length;
          i++;
        }
        out += '<div class="math-block">' + texHtml(mb2.join(' ')) + '</div>';
        continue;
      }

      if (RE_HR.test(ln)) { out += '<hr/>'; i++; continue; }

      if (ln.indexOf('|') >= 0 && i + 1 < ls.length && RE_TABLE_SEP.test(ls[i + 1])) {
        var tr = [ln, ls[i + 1]];
        i += 2;
        while (i < ls.length && !isBlank(ls[i]) && ls[i].indexOf('|') >= 0) { tr.push(ls[i]); i++; }
        out += tableHtml(tr);
        continue;
      }

      if (RE_QUOTE.test(ln)) {
        var qb = [];
        while (i < ls.length) {
          var qm = RE_QUOTE.exec(ls[i]);
          if (qm) { qb.push(qm[1]); i++; continue; }
          if (isBlank(ls[i])) { i++; break; }
          if (!isBlank(ls[i]) && qb.length && /^\s{1,2}\S/.test(ls[i])) { qb.push(ls[i].trim()); i++; continue; }
          break;
        }
        out += '<blockquote class="md-quote">' + blockHtml(qb.join('\n')) + '</blockquote>';
        continue;
      }

      if (RE_UL.test(ln) || RE_OL.test(ln)) {
        var isOl = RE_OL.test(ln);
        var lb = [ln];
        i++;
        while (i < ls.length) {
          var nl = ls[i];
          if (isBlank(nl)) {
            var nx = i + 1 < ls.length ? ls[i + 1] : '';
            if (/^\s{2,}\S/.test(nx)) { i++; continue; }
            break;
          }
          if (RE_UL.test(nl) || RE_OL.test(nl) || /^\s{2,}\S/.test(nl)) { lb.push(nl); i++; continue; }
          break;
        }
        out += listHtml(lb.join('\n'), isOl, 0);
        continue;
      }

      var pb = [ln];
      i++;
      while (i < ls.length && !isBlank(ls[i])) {
        var cl = ls[i];
        if (RE_FENCE.test(cl) || RE_HEAD.test(cl) || RE_HR.test(cl) || RE_QUOTE.test(cl) ||
            RE_UL.test(cl) || RE_OL.test(cl) || /^\s*<[a-zA-Z]/.test(cl) ||
            (cl.indexOf('|') >= 0 && i + 1 < ls.length && RE_TABLE_SEP.test(ls[i + 1])) ||
            cl.trim() === '$$' || cl.trim() === '\\[') break;
        pb.push(cl);
        i++;
      }
      out += '<p class="md-p">' + inlineMd(pb.join('\n')).replace(/\n/g, '<br/>') + '</p>';
    }
    return out;
  }
  /* ------------------------------------------------------------ sanitize */

  var ALLOW_TAG = (
    'a abbr b blockquote br caption code col colgroup dd del details div dl dt em figcaption figure ' +
    'h1 h2 h3 h4 h5 h6 hr i img ins kbd li mark ol p pre q s samp section small span strike strong sub ' +
    'summary sup table tbody td tfoot th thead tr u ul var wbr center ' +
    // 模型交回的整页 HTML 常用语义标签；不在白名单里会被整段丢掉，布局直接塌。
    'header footer main article aside nav canvas time progress meter bdi bdo ruby rt rp ' +
    'fieldset legend label optgroup option output template slot'
  ).split(' ');
  var VOID_TAG = 'br hr img wbr col'.split(' ');

  var ALLOW_ATTR = (
    'class style href title alt src width height align colspan rowspan start type cite datetime ' +
    'aria-hidden aria-label role open'
  ).split(' ');

  var TAG_MAP = { b: 'b', i: 'i', u: 'u', s: 's', q: 'q', var: 'var', strike: 's', center: 'div' };

  function safeStyle(v) {
    var s = String(v || '');
    if (/expression\s*\(|javascript:|behavior\s*:|url\s*\(\s*['"]?\s*javascript:/i.test(s)) return '';
    return s.replace(/[<>]/g, '');
  }

  function safeAttr(name, value) {
    var n = String(name).toLowerCase();
    var v = String(value == null ? '' : value);
    if (n.indexOf('on') === 0) return null;
    if (ALLOW_ATTR.indexOf(n) < 0 && !/^data-[a-z0-9-]+$/.test(n)) return null;
    if (n === 'href') {
      if (!SAFE_URL.test(v)) return null;
      return ['href', v];
    }
    if (n === 'src') {
      if (!/^data:image\//i.test(v)) return null;
      return ['src', v];
    }
    if (n === 'style') return ['style', safeStyle(v)];
    if (n === 'srcdoc') return null;
    return [n, v];
  }

  function sanitize(html) {
    var s = String(html == null ? '' : html);
    s = s.replace(/<script\b[\s\S]*?<\/script\s*>/gi, '');
    s = s.replace(/<script\b[^>]*>/gi, '');
    s = s.replace(/<!--[\s\S]*?-->/g, '');
    s = s.replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, '');
    s = s.replace(/<iframe\b[\s\S]*?<\/iframe\s*>/gi, '');
    s = s.replace(/<object\b[\s\S]*?<\/object\s*>/gi, '');
    s = s.replace(/<embed\b[^>]*>/gi, '');
    s = s.replace(/<link\b[^>]*>/gi, '');
    s = s.replace(/<meta\b[^>]*>/gi, '');
    s = s.replace(/<base\b[^>]*>/gi, '');
    s = s.replace(/<form\b[\s\S]*?<\/form\s*>/gi, '');
    s = s.replace(/<input\b[^>]*>/gi, '');
    s = s.replace(/<textarea\b[\s\S]*?<\/textarea\s*>/gi, '');
    s = s.replace(/<svg\b[\s\S]*?<\/svg\s*>/gi, function (m) {
      return m.replace(/\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '');
    });

    return s.replace(/<\/?([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[^<>]*?)?)(\/?)>/g,
      function (whole, name, attrs, selfClose) {
        var tag = String(name).toLowerCase();
        if (ALLOW_TAG.indexOf(tag) < 0) return '';
        tag = TAG_MAP[tag] || tag;
        if (whole.charAt(1) === '/') return '</' + tag + '>';
        var out = '<' + tag;
        var re = /([a-zA-Z_:][a-zA-Z0-9_:.-]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))/g;
        var m;
        var seen = {};
        re.lastIndex = 0;
        while ((m = re.exec(attrs)) !== null) {
          var key = m[1].toLowerCase();
          if (seen[key]) continue;
          var val = m[3] !== undefined ? m[3] : (m[4] !== undefined ? m[4] : m[5]);
          var kv = safeAttr(key, val);
          if (!kv) continue;
          seen[kv[0]] = 1;
          out += ' ' + kv[0] + '="' + attr(kv[1]) + '"';
        }
        if (selfClose || VOID_TAG.indexOf(tag) >= 0) return out + '/>';
        return out + '>';
      });
  }

  /* ------------------------------------------------------------ stylesheet */

  var CSS = [
    ':root{color-scheme:light dark}',
    'html,body{margin:0;padding:0;background:transparent;}',
    'body{font-family:"Noto Sans SC","PingFang SC","Helvetica Neue",Roboto,system-ui,sans-serif;',
    '  font-size:15px;line-height:1.62;color:var(--ds-fg);word-wrap:break-word;',
    '  -webkit-text-size-adjust:100%;}',
    '#dsh-root{--ds-fg:#0d1117;--ds-fg2:#5a6472;--ds-line:#e3e8ef;--ds-card:#f6f8fb;',
    '  --ds-accent:#0099ff;--ds-kw:#a626a4;--ds-str:#2f8a3f;--ds-num:#b76b00;',
    '  --ds-com:#8f98a6;--ds-fn:#0b6bbd;--ds-type:#0f766e;}',
    '@media (prefers-color-scheme:dark){#dsh-root{--ds-fg:#e8edf5;--ds-fg2:#8b96a6;',
    '  --ds-line:#2a323d;--ds-card:#1b222c;--ds-accent:#3cb4ff;--ds-kw:#d18bff;',
    '  --ds-str:#7bd88f;--ds-num:#f0b464;--ds-com:#6b7684;--ds-fn:#6fb6ff;',
    '  --ds-type:#5fd3c4;}}',
    '#dsh-root{color:var(--ds-fg);font:inherit;}',
    '#dsh-root>*:first-child{margin-top:0;}',
    '#dsh-root>*:last-child{margin-bottom:0;}',
    '.md-p{margin:0 0 .62em;}',
    '.md-h{margin:.9em 0 .42em;font-weight:650;line-height:1.32;}',
    '.md-h1{font-size:1.36em;}.md-h2{font-size:1.22em;}.md-h3{font-size:1.1em;}',
    '.md-h4,.md-h5,.md-h6{font-size:1em;}',
    '.md-h2{padding-bottom:.24em;border-bottom:1px solid var(--ds-line);}',
    '#dsh-root hr{border:0;border-top:1px solid var(--ds-line);margin:1em 0;}',
    '#dsh-root a{color:var(--ds-accent);text-decoration:none;}',
    '#dsh-root strong{font-weight:680;}',
    '#dsh-root mark{background:rgba(255,208,0,.35);color:inherit;border-radius:3px;padding:0 .12em;}',
    '#dsh-root del{opacity:.62;}',
    '.md-code-inline{font-family:"JetBrains Mono",ui-monospace,SFMono-Regular,Menlo,monospace;',
    '  font-size:.9em;background:var(--ds-card);border:1px solid var(--ds-line);',
    '  border-radius:5px;padding:.08em .34em;}',
    '.md-img{color:var(--ds-fg2);font-size:.92em;}',
    '.md-quote{margin:.5em 0;padding:.1em 0 .1em .78em;border-left:3px solid var(--ds-line);',
    '  color:var(--ds-fg2);}',
    '.md-quote>.md-p{margin:.2em 0;}',
    '#dsh-root ul,#dsh-root ol{margin:.28em 0 .62em;padding-left:1.28em;}',
    '#dsh-root li{margin:.16em 0;}',
    '#dsh-root li>ul,#dsh-root li>ol{margin:.14em 0;}',
    '.md-task{list-style:none;margin-left:-1.05em;}',
    '.md-task .md-check{display:inline-block;width:1.1em;color:var(--ds-accent);}',
    '.md-task.done{color:var(--ds-fg2);}',
    '.md-table-wrap{overflow-x:auto;margin:.5em 0 .72em;-webkit-overflow-scrolling:touch;}',
    '.md-table{border-collapse:collapse;font-size:.94em;min-width:50%;}',
    '.md-table th,.md-table td{border:1px solid var(--ds-line);padding:.34em .56em;text-align:left;',
    '  vertical-align:top;}',
    '.md-table th{background:var(--ds-card);font-weight:640;}',
    '.md-table .ta-c{text-align:center;}.md-table .ta-r{text-align:right;}',
    '.md-code{margin:.5em 0 .72em;border:1px solid var(--ds-line);border-radius:9px;',
    '  background:var(--ds-card);overflow:hidden;}',
    '.md-code-head{padding:.16em .68em;font-size:.72em;letter-spacing:.03em;color:var(--ds-fg2);',
    '  border-bottom:1px solid var(--ds-line);text-transform:uppercase;}',
    '.md-code pre{margin:0;padding:.62em .72em;overflow-x:auto;-webkit-overflow-scrolling:touch;}',
    '.md-code code{font-family:"JetBrains Mono",ui-monospace,SFMono-Regular,Menlo,monospace;',
    '  font-size:.86em;line-height:1.55;white-space:pre;}',
    '.hl-kw{color:var(--ds-kw);}.hl-str{color:var(--ds-str);}.hl-num{color:var(--ds-num);}',
    '.hl-com{color:var(--ds-com);font-style:italic;}.hl-fn{color:var(--ds-fn);}',
    '.hl-type{color:var(--ds-type);}',
    '.hl-add{color:var(--ds-str);background:rgba(34,197,94,.12);display:inline-block;width:100%;}',
    '.hl-del{color:#e5484d;background:rgba(229,72,77,.12);display:inline-block;width:100%;}',
    '.hl-meta{color:var(--ds-com);}',
    '.math-inline,.math-block{font-family:"Latin Modern Math","Cambria Math",serif;}',
    '.math-inline{white-space:nowrap;}',
    '.math-block{display:block;text-align:center;margin:.6em 0 .78em;overflow-x:auto;',
    '  padding:.15em 0;}',
    '.tex-frac{display:inline-flex;flex-direction:column;vertical-align:middle;',
    '  text-align:center;margin:0 .16em;font-size:.96em;}',
    '.tex-num{border-bottom:1px solid currentColor;padding:0 .16em;}',
    '.tex-den{padding:0 .16em;}',
    '.tex-sqrt{display:inline-flex;align-items:flex-start;}',
    '.tex-radical{margin-right:-.06em;}',
    '.tex-radicand{border-top:1px solid currentColor;padding:.02em .14em 0;}',
    '.tex-sqrt-idx{font-size:.62em;vertical-align:super;}',
    '.tex-op{display:inline-flex;flex-direction:column;vertical-align:middle;',
    '  align-items:center;margin:0 .12em;font-size:.94em;}',
    '.tex-op-sym{font-size:1.32em;line-height:1;}',
    '.tex-op-sub,.tex-op-sup{font-size:.6em;line-height:1.05;}',
    '.tex-matrix-wrap{display:inline-flex;align-items:center;vertical-align:middle;}',
    '.tex-matrix-br{font-size:1.9em;line-height:1;font-weight:300;}',
    '.tex-matrix,.tex-align{border-collapse:collapse;margin:0 .16em;}',
    '.tex-matrix td{padding:.06em .34em;text-align:center;}',
    '.tex-align td{padding:.06em .12em;}',
    '.tex-cases td{text-align:left;}',
    '.tex-mark{display:inline-block;width:0;overflow:visible;}',
    '.tex-operatorname{font-style:normal;}',
    '.tex-underline{text-decoration:underline;}',
    '.tex-mathbb{font-family:"Latin Modern Math",serif;font-weight:600;}',
    '.tex-mathcal{font-style:italic;}',
    '#dsh-root details{margin:.4em 0;}',
    '#dsh-root summary{cursor:pointer;color:var(--ds-accent);}',
    '#dsh-root img{max-width:100%;height:auto;border-radius:8px;}',
    '#dsh-root table:not(.md-table):not(.tex-matrix):not(.tex-align){border-collapse:collapse;',
    '  font-size:.94em;}',
    '#dsh-root table:not(.md-table) th,#dsh-root table:not(.md-table) td{',
    '  border:1px solid var(--ds-line);padding:.3em .5em;}'
  ].join('\n');

  /* ------------------------------------------------------------ public */

  function body(text) { return sanitize(blockHtml(text)); }

  /*
   * 整份 HTML 文档走这里：不再过 markdown，直接清洗。
   *
   * body() 是把「人写的 markdown 文本」当源码解析，模型交回的整页 HTML 一旦进了 blockHtml，
   * 每个块级标签都会被当成段落转义显示 —— 用户看到的是源码而不是页面。
   */
  function doc(html) { return sanitize(String(html == null ? '' : html)); }

  function render(text) { return body(text); }

  global.DSH_RENDER = {
    version: VERSION,
    render: render,
    body: body,
    doc: doc,
    md: body,
    tex: texHtml,
    sanitize: sanitize,
    css: function () { return CSS; }
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = global.DSH_RENDER;
})(typeof globalThis !== 'undefined' ? globalThis : this);
