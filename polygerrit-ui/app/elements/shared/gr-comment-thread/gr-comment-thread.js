/**
 * @license
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import '../../../styles/shared-styles.js';
import '../gr-rest-api-interface/gr-rest-api-interface.js';
import '../gr-storage/gr-storage.js';
import '../gr-comment/gr-comment.js';
import {dom} from '@polymer/polymer/lib/legacy/polymer.dom.js';
import {GestureEventListeners} from '@polymer/polymer/lib/mixins/gesture-event-listeners.js';
import {LegacyElementMixin} from '@polymer/polymer/lib/legacy/legacy-element-mixin.js';
import {PolymerElement} from '@polymer/polymer/polymer-element.js';
import {htmlTemplate} from './gr-comment-thread_html.js';
import {KeyboardShortcutMixin} from '../../../mixins/keyboard-shortcut-mixin/keyboard-shortcut-mixin.js';
import {GerritNav} from '../../core/gr-navigation/gr-navigation.js';
import {appContext} from '../../../services/app-context.js';
import {SpecialFilePath} from '../../../constants/constants.js';
import {computeDisplayPath} from '../../../utils/path-list-util.js';
import {sortComments} from '../../diff/gr-comment-api/gr-comment-api.js';

const UNRESOLVED_EXPAND_COUNT = 5;
const NEWLINE_PATTERN = /\n/g;

/**
 * @extends PolymerElement
 */
class GrCommentThread extends KeyboardShortcutMixin(GestureEventListeners(
    LegacyElementMixin(PolymerElement))) {
  // KeyboardShortcutMixin Not used in this element rather other elements tests

  static get template() { return htmlTemplate; }

  static get is() { return 'gr-comment-thread'; }
  /**
   * Fired when the thread should be discarded.
   *
   * @event thread-discard
   */

  /**
   * Fired when a comment in the thread is permanently modified.
   *
   * @event thread-changed
   */

  /**
   * gr-comment-thread exposes the following attributes that allow a
   * diff widget like gr-diff to show the thread in the right location:
   *
   * line-num:
   *     1-based line number or undefined if it refers to the entire file.
   *
   * comment-side:
   *     "left" or "right". These indicate which of the two diffed versions
   *     the comment relates to. In the case of unified diff, the left
   *     version is the one whose line number column is further to the left.
   *
   * range:
   *     The range of text that the comment refers to (start_line,
   *     start_character, end_line, end_character), serialized as JSON. If
   *     set, range's end_line will have the same value as line-num. Line
   *     numbers are 1-based, char numbers are 0-based. The start position
   *     (start_line, start_character) is inclusive, and the end position
   *     (end_line, end_character) is exclusive.
   */
  static get properties() {
    return {
      changeNum: String,
      comments: {
        type: Array,
        value() { return []; },
      },
      /**
       * @type {?{start_line: number, start_character: number, end_line: number,
       *          end_character: number}}
       */
      range: {
        type: Object,
        reflectToAttribute: true,
      },
      keyEventTarget: {
        type: Object,
        value() { return document.body; },
      },
      commentSide: {
        type: String,
        reflectToAttribute: true,
      },
      patchNum: String,
      path: String,
      projectName: {
        type: String,
        observer: '_projectNameChanged',
      },
      hasDraft: {
        type: Boolean,
        notify: true,
        reflectToAttribute: true,
      },
      isOnParent: {
        type: Boolean,
        value: false,
      },
      parentIndex: {
        type: Number,
        value: null,
      },
      rootId: {
        type: String,
        notify: true,
        computed: '_computeRootId(comments.*)',
      },
      /**
       * If this is true, the comment thread also needs to have the change and
       * line properties property set
       */
      showFilePath: {
        type: Boolean,
        value: false,
      },
      /** Necessary only if showFilePath is true or when used with gr-diff */
      lineNum: {
        type: Number,
        reflectToAttribute: true,
      },
      unresolved: {
        type: Boolean,
        notify: true,
        reflectToAttribute: true,
      },
      _showActions: Boolean,
      _lastComment: Object,
      _orderedComments: Array,
      _projectConfig: Object,
      isRobotComment: {
        type: Boolean,
        value: false,
        reflectToAttribute: true,
      },
      showFileName: {
        type: Boolean,
        value: true,
      },
      showPatchset: {
        type: Boolean,
        value: true,
      },
    };
  }

  static get observers() {
    return [
      '_commentsChanged(comments.*)',
    ];
  }

  get keyBindings() {
    return {
      'e shift+e': '_handleEKey',
    };
  }

  constructor() {
    super();
    this.reporting = appContext.reportingService;
    this.flagsService = appContext.flagsService;
  }

  /** @override */
  created() {
    super.created();
    this.addEventListener('comment-update',
        e => this._handleCommentUpdate(e));
  }

  /** @override */
  attached() {
    super.attached();
    this._getLoggedIn().then(loggedIn => {
      this._showActions = loggedIn;
    });
    this._setInitialExpandedState();
  }

  addOrEditDraft(opt_lineNum, opt_range) {
    const lastComment = this.comments[this.comments.length - 1] || {};
    if (lastComment.__draft) {
      const commentEl = this._commentElWithDraftID(
          lastComment.id || lastComment.__draftID);
      commentEl.editing = true;

      // If the comment was collapsed, re-open it to make it clear which
      // actions are available.
      commentEl.collapsed = false;
    } else {
      const range = opt_range ? opt_range :
        lastComment ? lastComment.range : undefined;
      const unresolved = lastComment ? lastComment.unresolved : undefined;
      this.addDraft(opt_lineNum, range, unresolved);
    }
  }

  addDraft(opt_lineNum, opt_range, opt_unresolved) {
    const draft = this._newDraft(opt_lineNum, opt_range);
    draft.__editing = true;
    draft.unresolved = opt_unresolved === false ? opt_unresolved : true;
    this.push('comments', draft);
  }

  fireRemoveSelf() {
    this.dispatchEvent(new CustomEvent('thread-discard',
        {detail: {rootId: this.rootId}, bubbles: false}));
  }

  _getDiffUrlForPath(path) {
    if (this.comments[0].__draft) {
      return GerritNav.getUrlForDiffById(this.changeNum,
          this.projectName, path, this.patchNum);
    }
    return GerritNav.getUrlForComment(this.changeNum, this.projectName,
        this.comments[0].id);
  }

  _getDiffUrlForComment(projectName, changeNum, path, patchNum) {
    if ((this.comments.length && this.comments[0].side === 'PARENT') ||
      this.comments[0].__draft) {
      return GerritNav.getUrlForDiffById(changeNum,
          projectName, path, patchNum, null, this.lineNum);
    }
    return GerritNav.getUrlForComment(this.changeNum, this.projectName,
        this.comments[0].id);
  }

  _isPatchsetLevelComment(path) {
    return path === SpecialFilePath.PATCHSET_LEVEL_COMMENTS;
  }

  _computeDisplayPath(path) {
    const displayPath = computeDisplayPath(path);
    if (displayPath === SpecialFilePath.PATCHSET_LEVEL_COMMENTS) {
      return `Patchset`;
    }
    return displayPath;
  }

  _computeDisplayLine() {
    if (this.lineNum) return `#${this.lineNum}`;
    // If range is set, then lineNum equals the end line of the range.
    if (!this.lineNum && !this.range) {
      if (this.path === SpecialFilePath.PATCHSET_LEVEL_COMMENTS) {
        return '';
      }
      return 'FILE';
    }
    if (this.range) return `#${this.range.end_line}`;
    return '';
  }

  _getLoggedIn() {
    return this.$.restAPI.getLoggedIn();
  }

  _commentsChanged() {
    this._orderedComments = sortComments(this.comments);
    this.updateThreadProperties();
  }

  updateThreadProperties() {
    if (this._orderedComments.length) {
      this._lastComment = this._getLastComment();
      this.unresolved = this._lastComment.unresolved;
      this.hasDraft = this._lastComment.__draft;
      this.isRobotComment = !!(this._lastComment.robot_id);
    }
  }

  _shouldDisableAction(_showActions, _lastComment) {
    return !_showActions || !_lastComment || !!_lastComment.__draft;
  }

  _hideActions(_showActions, _lastComment) {
    return this._shouldDisableAction(_showActions, _lastComment) ||
      !!_lastComment.robot_id;
  }

  _getLastComment() {
    return this._orderedComments[this._orderedComments.length - 1] || {};
  }

  _handleEKey(e) {
    if (this.shouldSuppressKeyboardShortcut(e)) { return; }

    // Don’t preventDefault in this case because it will render the event
    // useless for other handlers (other gr-comment-thread elements).
    if (e.detail.keyboardEvent.shiftKey) {
      this._expandCollapseComments(true);
    } else {
      if (this.modifierPressed(e)) { return; }
      this._expandCollapseComments(false);
    }
  }

  _expandCollapseComments(actionIsCollapse) {
    const comments =
        this.root.querySelectorAll('gr-comment');
    for (const comment of comments) {
      comment.collapsed = actionIsCollapse;
    }
  }

  /**
   * Sets the initial state of the comment thread.
   * Expands the thread if one of the following is true:
   * - last {UNRESOLVED_EXPAND_COUNT} comments expanded by default if the
   * thread is unresolved,
   * - it's a robot comment.
   */
  _setInitialExpandedState() {
    if (this._orderedComments) {
      for (let i = 0; i < this._orderedComments.length; i++) {
        const comment = this._orderedComments[i];
        const isRobotComment = !!comment.robot_id;
        // False if it's an unresolved comment under UNRESOLVED_EXPAND_COUNT.
        const resolvedThread = !this.unresolved ||
              this._orderedComments.length - i - 1 >= UNRESOLVED_EXPAND_COUNT;
        if (comment.collapsed === undefined) {
          comment.collapsed = !isRobotComment && resolvedThread;
        }
      }
    }
  }

  _createReplyComment(content, opt_isEditing,
      opt_unresolved) {
    this.reporting.recordDraftInteraction();
    const reply = this._newReply(
        this._orderedComments[this._orderedComments.length - 1].id,
        content,
        opt_unresolved);

    // If there is currently a comment in an editing state, add an attribute
    // so that the gr-comment knows not to populate the draft text.
    for (let i = 0; i < this.comments.length; i++) {
      if (this.comments[i].__editing) {
        reply.__otherEditing = true;
        break;
      }
    }

    if (opt_isEditing) {
      reply.__editing = true;
    }

    this.push('comments', reply);

    if (!opt_isEditing) {
      // Allow the reply to render in the dom-repeat.
      this.async(() => {
        const commentEl = this._commentElWithDraftID(reply.__draftID);
        commentEl.save();
      }, 1);
    }
  }

  _isDraft(comment) {
    return !!comment.__draft;
  }

  /**
   * @param {boolean=} opt_quote
   */
  _processCommentReply(opt_quote) {
    const comment = this._lastComment;
    let quoteStr;
    if (opt_quote) {
      const msg = comment.message;
      quoteStr = '> ' + msg.replace(NEWLINE_PATTERN, '\n> ') + '\n\n';
    }
    this._createReplyComment(quoteStr, true, comment.unresolved);
  }

  _handleCommentReply() {
    this._processCommentReply();
  }

  _handleCommentQuote() {
    this._processCommentReply(true);
  }

  _handleCommentAck() {
    this._createReplyComment('Ack', false, false);
  }

  _handleCommentDone() {
    this._createReplyComment('Done', false, false);
  }

  _handleCommentFix(e) {
    const comment = e.detail.comment;
    const msg = comment.message;
    const quoteStr = '> ' + msg.replace(NEWLINE_PATTERN, '\n> ') + '\n\n';
    const response = quoteStr + 'Please fix.';
    this._createReplyComment(response, false, true);
  }

  _commentElWithDraftID(id) {
    const els = this.root.querySelectorAll('gr-comment');
    for (const el of els) {
      if (el.comment.id === id || el.comment.__draftID === id) {
        return el;
      }
    }
    return null;
  }

  _newReply(inReplyTo, opt_message, opt_unresolved) {
    const d = this._newDraft();
    d.in_reply_to = inReplyTo;
    if (opt_message != null) {
      d.message = opt_message;
    }
    if (opt_unresolved !== undefined) {
      d.unresolved = opt_unresolved;
    }
    return d;
  }

  /**
   * @param {number=} opt_lineNum
   * @param {!Object=} opt_range
   */
  _newDraft(opt_lineNum, opt_range) {
    const d = {
      __draft: true,
      __draftID: Math.random().toString(36),
      __date: new Date(),
    };

    // For replies, always use same meta info as root.
    if (this.comments && this.comments.length >= 1) {
      const rootComment = this.comments[0];
      [
        'path',
        'patchNum',
        'side',
        '__commentSide',
        'line',
        'range',
        'parent',
      ].forEach(key => {
        if (rootComment.hasOwnProperty(key)) {
          d[key] = rootComment[key];
        }
      });
    } else {
      // Set meta info for root comment.
      d.path = this.path;
      d.patchNum = this.patchNum;
      d.side = this._getSide(this.isOnParent);
      d.__commentSide = this.commentSide;

      if (opt_lineNum) {
        d.line = opt_lineNum;
      }
      if (opt_range) {
        d.range = opt_range;
      }
      if (this.parentIndex) {
        d.parent = this.parentIndex;
      }
    }
    return d;
  }

  _getSide(isOnParent) {
    if (isOnParent) { return 'PARENT'; }
    return 'REVISION';
  }

  _computeRootId(comments) {
    // Keep the root ID even if the comment was removed, so that notification
    // to sync will know which thread to remove.
    if (!comments.base.length) { return this.rootId; }
    const rootComment = comments.base[0];
    return rootComment.id || rootComment.__draftID;
  }

  _handleCommentDiscard(e) {
    const diffCommentEl = dom(e).rootTarget;
    const comment = diffCommentEl.comment;
    const idx = this._indexOf(comment, this.comments);
    if (idx == -1) {
      throw Error('Cannot find comment ' +
          JSON.stringify(diffCommentEl.comment));
    }
    this.splice('comments', idx, 1);
    if (this.comments.length === 0) {
      this.fireRemoveSelf();
    }
    this._handleCommentSavedOrDiscarded(e);

    // Check to see if there are any other open comments getting edited and
    // set the local storage value to its message value.
    for (const changeComment of this.comments) {
      if (changeComment.__editing) {
        const commentLocation = {
          changeNum: this.changeNum,
          patchNum: this.patchNum,
          path: changeComment.path,
          line: changeComment.line,
        };
        return this.$.storage.setDraftComment(commentLocation,
            changeComment.message);
      }
    }
  }

  _handleCommentSavedOrDiscarded(e) {
    this.dispatchEvent(new CustomEvent('thread-changed',
        {detail: {rootId: this.rootId, path: this.path},
          bubbles: false}));
  }

  _handleCommentUpdate(e) {
    const comment = e.detail.comment;
    const index = this._indexOf(comment, this.comments);
    if (index === -1) {
      // This should never happen: comment belongs to another thread.
      console.warn('Comment update for another comment thread.');
      return;
    }
    this.set(['comments', index], comment);
    // Because of the way we pass these comment objects around by-ref, in
    // combination with the fact that Polymer does dirty checking in
    // observers, the this.set() call above will not cause a thread update in
    // some situations.
    this.updateThreadProperties();
  }

  _indexOf(comment, arr) {
    for (let i = 0; i < arr.length; i++) {
      const c = arr[i];
      if ((c.__draftID != null && c.__draftID == comment.__draftID) ||
          (c.id != null && c.id == comment.id)) {
        return i;
      }
    }
    return -1;
  }

  _computeHostClass(unresolved) {
    if (this.isRobotComment) {
      return 'robotComment';
    }
    return unresolved ? 'unresolved' : '';
  }

  /**
   * Load the project config when a project name has been provided.
   *
   * @param {string} name The project name.
   */
  _projectNameChanged(name) {
    if (!name) { return; }
    this.$.restAPI.getProjectConfig(name).then(config => {
      this._projectConfig = config;
    });
  }
}

customElements.define(GrCommentThread.is, GrCommentThread);
