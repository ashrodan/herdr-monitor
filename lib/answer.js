// Answer an agent's question form by tapping an option on the page: the
// tap becomes the keys a person would press.
//
// omp's Ask form (packages/tui/src/overlays/ask-dialog.ts, read from omp
// 18.15): rows are the options in order, then "Other (type your own)".
// ↑/↓ move and stop at the ends; Enter picks (single choice) and moves on to
// the next question, or submits when there is only one; Space toggles (multi
// choice); Tab/←/→ move between questions and wrap, with a last "Submit" tab.
// The question on screen tells us where the form is now.

const BOX = /[│╭╮╰╯├┤┬┴┼─]/g;
export const norm = (s) => String(s).replace(BOX, ' ').replace(/\s+/g, ' ').trim().toLowerCase();

// Which question the form shows: its index, questions.length for the
// Submit tab, or -1 when no form is on screen.
export function currentQuestion(screen, questions) {
  const at = screen.lastIndexOf('Ask');
  if (at < 0) return -1;
  const body = norm(screen.slice(at));
  const hit = questions.findIndex((q) => body.includes(norm(q).slice(0, 50)));
  if (hit >= 0) return hit;
  return questions.length > 1 && /submit/.test(body) ? questions.length : -1;
}

// The keys for one tap. action "pick": option `option` of question `q`
// (rows = its options + Other). action "submit": go to Submit and press Enter.
export function answerKeys({ current, total, q, option, rows, multi, action = 'pick' }) {
  const keys = [];
  const target = action === 'submit' ? total : q;
  if (total > 1) {
    const d = target - current;
    for (let i = 0; i < Math.abs(d); i++) keys.push(d > 0 ? 'right' : 'left');
  }
  if (action === 'submit') { keys.push('enter'); return keys; }
  for (let i = 0; i < rows; i++) keys.push('up');
  for (let i = 0; i < option; i++) keys.push('down');
  keys.push(multi ? 'space' : 'enter');
  return keys;
}
