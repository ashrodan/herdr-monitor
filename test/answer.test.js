import { test } from 'node:test';
import assert from 'node:assert/strict';
import { answerKeys, currentQuestion } from '../lib/answer.js';

const SCREEN = `older output mentioning Theme
╭─ Ask ────────────────────────────────╮
│  Database    Theme    Deploy    Submit │
│ Which database should the example app use for its local   │
│ development setup (SQLITE_PATH, DATABASE_URL)?             │
├──────────────────────────────────────┤
│   SQLite (Recommended)                                   │`;
const QS = ['Which database should the example app use for its local development setup (SQLITE_PATH, DATABASE_URL)?', 'Which theme for the docs site?', 'Deploy now?'];

test('currentQuestion: matches the wrapped question inside the Ask box', () => {
  assert.equal(currentQuestion(SCREEN, QS), 0);
  assert.equal(currentQuestion(SCREEN.replace(/Which[\s\S]*URL\)\?/, 'Which theme for the docs site?'), QS), 1);
  assert.equal(currentQuestion('╭─ Ask ─╮\n│ Database Theme Deploy Submit │\n│ 1 unanswered question; enter still submits. │', QS), 3);
  assert.equal(currentQuestion('no form here', QS), -1);
});

test('answerKeys: move to the question, to the top, down to the option, pick', () => {
  assert.deepEqual(answerKeys({ current: 0, total: 3, q: 2, option: 1, rows: 3, multi: false }), ['right', 'right', 'up', 'up', 'up', 'down', 'enter']);
  assert.deepEqual(answerKeys({ current: 2, total: 3, q: 0, option: 0, rows: 2, multi: true }), ['left', 'left', 'up', 'up', 'space']);
  assert.deepEqual(answerKeys({ current: 0, total: 1, q: 0, option: 2, rows: 4, multi: false }), ['up', 'up', 'up', 'up', 'down', 'down', 'enter']);
  assert.deepEqual(answerKeys({ current: 1, total: 3, action: 'submit' }), ['right', 'right', 'enter']);
});
