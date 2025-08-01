/**
 * @license
 * Copyright 2015 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import '../../../styles/gr-a11y-styles';
import '../../../styles/shared-styles';
import '../../diff/gr-diff-host/gr-diff-host';
import '../../diff/gr-diff-preferences-dialog/gr-diff-preferences-dialog';
import '../../edit/gr-edit-file-controls/gr-edit-file-controls';
import '../../shared/gr-button/gr-button';
import '../../shared/gr-cursor-manager/gr-cursor-manager';
import '../../shared/gr-icon/gr-icon';
import '../../shared/gr-select/gr-select';
import '../../shared/gr-tooltip-content/gr-tooltip-content';
import '../../shared/gr-copy-clipboard/gr-copy-clipboard';
import '../../shared/gr-file-status/gr-file-status';
import '../gr-comments-summary/gr-comments-summary';
import {assertIsDefined} from '../../../utils/common-util';
import {asyncForeach} from '../../../utils/async-util';
import {FilesExpandedState} from '../gr-file-list-constants';
import {diffFilePaths, pluralize} from '../../../utils/string-util';
import {navigationToken} from '../../core/gr-navigation/gr-navigation';
import {getAppContext} from '../../../services/app-context';
import {
  DiffViewMode,
  FileInfoStatus,
  ScrollMode,
  SpecialFilePath,
} from '../../../constants/constants';
import {descendedFromClass, Key} from '../../../utils/dom-util';
import {
  computeDisplayPath,
  computeTruncatedPath,
  isMagicPath,
} from '../../../utils/path-list-util';
import {customElement, property, query, state} from 'lit/decorators.js';
import {
  BasePatchSetNum,
  EDIT,
  FileInfo,
  NumericChangeId,
  PARENT,
  PatchRange,
  RevisionPatchSetNum,
} from '../../../types/common';
import {DiffPreferencesInfo} from '../../../types/diff';
import {GrDiffHost} from '../../diff/gr-diff-host/gr-diff-host';
import {GrDiffPreferencesDialog} from '../../diff/gr-diff-preferences-dialog/gr-diff-preferences-dialog';
import {GrDiffCursor} from '../../../embed/diff/gr-diff-cursor/gr-diff-cursor';
import {GrCursorManager} from '../../shared/gr-cursor-manager/gr-cursor-manager';
import {ChangeComments} from '../../diff/gr-comment-api/gr-comment-api';
import {ParsedChangeInfo, PatchSetFile} from '../../../types/types';
import {Interaction, Timing} from '../../../constants/reporting';
import {RevisionInfo} from '../../shared/revision-info/revision-info';
import {select} from '../../../utils/observable-util';
import {resolve} from '../../../models/dependency';
import {browserModelToken} from '../../../models/browser/browser-model';
import {commentsModelToken} from '../../../models/comments/comments-model';
import {checksModelToken, RunResult} from '../../../models/checks/checks-model';
import {changeModelToken} from '../../../models/change/change-model';
import {filesModelToken} from '../../../models/change/files-model';
import {ShortcutController} from '../../lit/shortcut-controller';
import {
  css,
  html,
  LitElement,
  nothing,
  PropertyValues,
  TemplateResult,
} from 'lit';
import {Shortcut} from '../../../services/shortcuts/shortcuts-config';
import {fire} from '../../../utils/event-util';
import {a11yStyles} from '../../../styles/gr-a11y-styles';
import {sharedStyles} from '../../../styles/shared-styles';
import {ValueChangedEvent} from '../../../types/events';
import {subscribe} from '../../lit/subscription-controller';
import {when} from 'lit/directives/when.js';
import {classMap} from 'lit/directives/class-map.js';
import {incrementalRepeat} from '../../lit/incremental-repeat';
import {ifDefined} from 'lit/directives/if-defined.js';
import {
  changeViewModelToken,
  createChangeUrl,
} from '../../../models/views/change';
import {userModelToken} from '../../../models/user/user-model';
import {pluginLoaderToken} from '../../shared/gr-js-api-interface/gr-plugin-loader';
import {
  FileMode,
  fileModeToString,
  formatBytes,
} from '../../../utils/file-util';
import {ChecksIcon, iconFor} from '../../../models/checks/checks-util';

export const DEFAULT_NUM_FILES_SHOWN = 200;

const WARN_SHOW_ALL_THRESHOLD = 1000;

const SIZE_BAR_MAX_WIDTH = 61;
const SIZE_BAR_GAP_WIDTH = 1;
const SIZE_BAR_MIN_WIDTH = 1.5;

const FILE_ROW_CLASS = 'file-row';

export interface NormalizedFileInfo extends FileInfo {
  __path: string;
}

interface PatchChange {
  inserted: number;
  deleted: number;
  size_delta_inserted: number;
  size_delta_deleted: number;
  total_size: number;
}

function createDefaultPatchChange(): PatchChange {
  // Use function instead of const to prevent unexpected changes in the default
  // values.
  return {
    inserted: 0,
    deleted: 0,
    size_delta_inserted: 0,
    size_delta_deleted: 0,
    total_size: 0,
  };
}

interface SizeBarLayout {
  maxInserted: number;
  maxDeleted: number;
  maxAdditionWidth: number;
  maxDeletionWidth: number;
  additionOffset: number;
}

function createDefaultSizeBarLayout(): SizeBarLayout {
  // Use function instead of const to prevent unexpected changes in the default
  // values.
  return {
    maxInserted: 0,
    maxDeleted: 0,
    maxAdditionWidth: 0,
    maxDeletionWidth: 0,
    additionOffset: 0,
  };
}

interface FileRow {
  file: PatchSetFile;
  element: HTMLElement;
}

/**
 * Type for FileInfo
 *
 * This should match with the type returned from `files` API plus
 * additional info like `__path`.
 *
 * @typedef {Object} FileInfo
 * @property {string} __path
 * @property {?string} old_path
 * @property {number} size
 * @property {number} size_delta - fallback to 0 if not present in api
 * @property {number} lines_deleted - fallback to 0 if not present in api
 * @property {number} lines_inserted - fallback to 0 if not present in api
 */

declare global {
  interface HTMLElementEventMap {
    'files-shown-changed': CustomEvent<{length: number}>;
    'files-expanded-changed': ValueChangedEvent<FilesExpandedState>;
    'diff-prefs-changed': ValueChangedEvent<DiffPreferencesInfo>;
  }
  interface HTMLElementTagNameMap {
    'gr-file-list': GrFileList;
  }
}
@customElement('gr-file-list')
export class GrFileList extends LitElement {
  @query('#diffPreferencesDialog')
  diffPreferencesDialog?: GrDiffPreferencesDialog;

  get patchRange(): PatchRange | undefined {
    if (!this.patchNum) return undefined;
    return {
      patchNum: this.patchNum,
      basePatchNum: this.basePatchNum,
    };
  }

  // Private but used in tests.
  @state()
  patchNum?: RevisionPatchSetNum;

  // Private but used in tests.
  @state()
  basePatchNum: BasePatchSetNum = PARENT;

  @property({type: Number})
  changeNum?: NumericChangeId;

  @property({type: Object})
  changeComments?: ChangeComments;

  @property({type: Array})
  checkResults?: RunResult[];

  @state() selectedIndex = 0;

  @property({type: Object})
  change?: ParsedChangeInfo;

  @state()
  diffViewMode?: DiffViewMode;

  @property({type: Boolean})
  editMode = false;

  private _filesExpanded = FilesExpandedState.NONE;

  get filesExpanded() {
    return this._filesExpanded;
  }

  set filesExpanded(filesExpanded: FilesExpandedState) {
    if (this._filesExpanded === filesExpanded) return;
    const oldFilesExpanded = this._filesExpanded;
    this._filesExpanded = filesExpanded;
    fire(this, 'files-expanded-changed', {value: this._filesExpanded});
    this.requestUpdate('filesExpanded', oldFilesExpanded);
  }

  // Private but used in tests.
  @state()
  files: NormalizedFileInfo[] = [];

  @state()
  private modifiedFiles: NormalizedFileInfo[] = [];

  @state()
  private unmodifiedFiles: NormalizedFileInfo[] = [];

  // Private but used in tests.
  @state() filesLeftBase: NormalizedFileInfo[] = [];

  @state() private filesRightBase: NormalizedFileInfo[] = [];

  // Private but used in tests.
  @state()
  loggedIn = false;

  /**
   * List of paths of files that are marked as reviewed. Direct model
   * subscription.
   */
  @state()
  reviewed: string[] = [];

  @state()
  diffPrefs?: DiffPreferencesInfo;

  @state() numFilesShown = 0;

  @state()
  fileListIncrement: number = DEFAULT_NUM_FILES_SHOWN;

  // Private but used in tests.
  @state()
  expandedFiles: PatchSetFile[] = [];

  // Private but used in tests.
  @state()
  showSizeBars = true;

  // For merge commits vs Auto Merge, an extra file row is shown detailing the
  // files that were merged without conflict. These files are also passed to any
  // plugins.
  @state()
  private cleanlyMergedPaths: string[] = [];

  // Private but used in tests.
  @state()
  cleanlyMergedOldPaths: string[] = [];

  private cancelForEachDiff?: () => void;

  @state()
  private dynamicHeaderEndpoints?: string[];

  @state()
  private dynamicContentEndpoints?: string[];

  @state()
  private dynamicSummaryEndpoints?: string[];

  @state()
  private dynamicPrependedHeaderEndpoints?: string[];

  @state()
  private dynamicPrependedContentEndpoints?: string[];

  private readonly reporting = getAppContext().reportingService;

  private readonly restApiService = getAppContext().restApiService;

  private readonly getPluginLoader = resolve(this, pluginLoaderToken);

  private readonly getUserModel = resolve(this, userModelToken);

  private readonly getChangeModel = resolve(this, changeModelToken);

  private readonly getFilesModel = resolve(this, filesModelToken);

  private readonly getCommentsModel = resolve(this, commentsModelToken);

  private readonly getChecksModel = resolve(this, checksModelToken);

  private readonly getBrowserModel = resolve(this, browserModelToken);

  shortcutsController = new ShortcutController(this);

  private readonly getNavigation = resolve(this, navigationToken);

  private readonly getViewModel = resolve(this, changeViewModelToken);

  // private but used in test
  fileCursor = new GrCursorManager();

  // private but used in test
  diffCursor?: GrDiffCursor;

  static override get styles() {
    return [
      a11yStyles,
      sharedStyles,
      css`
        :host {
          display: block;
        }
        .row {
          align-items: center;
          border-top: 1px solid var(--border-color);
          display: flex;
          min-height: calc(var(--line-height-normal) + 2 * var(--spacing-s));
          padding: var(--spacing-xs) var(--spacing-l);
        }
        /* The class defines a content visible only to screen readers */
        .noCommentsScreenReaderText {
          opacity: 0;
          max-width: 1px;
          overflow: hidden;
          display: none;
          vertical-align: top;
        }
        div[role='gridcell']
          > div.comments
          > span:empty
          + span:empty
          + span.noCommentsScreenReaderText {
          /* inline-block instead of block, such that it can control width */
          display: inline-block;
        }
        :host(.editMode) .hideOnEdit {
          display: none;
        }
        .showOnEdit {
          display: none;
        }
        :host(.editMode) .showOnEdit {
          display: initial;
        }
        .invisible {
          visibility: hidden;
        }
        .header-row {
          background-color: var(--background-color-secondary);
        }
        .controlRow {
          align-items: center;
          display: flex;
          height: 2.25em;
          justify-content: center;
        }
        .controlRow.invisible,
        .show-hide.invisible {
          display: none;
        }
        .reviewed {
          align-items: center;
          display: inline-flex;
        }
        .reviewed {
          display: inline-block;
          text-align: left;
          width: 1.5em;
        }
        .file-row {
          cursor: pointer;
        }
        .file-row.expanded {
          border-bottom: 1px solid var(--border-color);
          position: -webkit-sticky;
          position: sticky;
          top: 0;
          /* Has to visible above the diff view, and by default has a lower
            z-index. setting to 1 places it directly above. */
          z-index: 1;
        }
        .separator-row {
          background-color: var(--background-color-secondary);
          border-top: 1px solid var(--border-color);
          color: var(--deemphasized-text-color);
          padding: var(--spacing-l);
        }
        .separator-row .path {
          color: var(--deemphasized-text-color);
          font-style: italic;
        }
        .separator-row .path gr-icon {
          margin-left: var(--spacing-s);
        }
        .file-row:hover {
          background-color: var(--hover-background-color);
        }
        .file-row.selected {
          background-color: var(--selection-background-color);
        }
        .file-row.expanded,
        .file-row.expanded:hover {
          background-color: var(--expanded-background-color);
        }
        .status {
          margin-right: var(--spacing-m);
          display: flex;
          width: 20px;
          justify-content: flex-end;
        }
        .status.extended {
          width: 56px;
        }
        .status > * {
          display: block;
        }
        .header-row .status .content {
          width: 20px;
          text-align: center;
        }
        .path {
          flex: 1;
          /* Wrap it into multiple lines if too long. */
          white-space: normal;
          word-break: break-word;
        }
        .oldPath {
          color: var(--deemphasized-text-color);
        }
        .header-stats {
          text-align: center;
          min-width: 7.5em;
        }
        .stats {
          text-align: right;
          min-width: 7.5em;
        }
        .comments {
          padding-left: var(--spacing-l);
          min-width: 7.5em;
          white-space: nowrap;
        }
        .row:not(.header-row) .stats,
        .total-stats {
          font-family: var(--monospace-font-family);
          font-size: var(--font-size-mono);
          line-height: var(--line-height-mono);
          display: flex;
        }
        .sizeBars {
          margin-left: var(--spacing-m);
          min-width: 7em;
          text-align: center;
        }
        .sizeBars.hide {
          display: none;
        }
        .added,
        .removed {
          display: inline-block;
          min-width: 3.5em;
        }
        .added {
          color: var(--positive-green-text-color);
          text-align: left;
          min-width: 4em;
          padding-left: var(--spacing-s);
        }
        .removed {
          color: var(--negative-red-text-color);
        }
        .drafts {
          color: var(--error-foreground);
          font-weight: var(--font-weight-medium);
        }
        .show-hide-icon:focus {
          outline: none;
        }
        .show-hide {
          margin-left: var(--spacing-s);
          width: 1.9em;
        }
        .fileListButton {
          margin: var(--spacing-m);
        }
        .totalChanges {
          justify-content: flex-end;
          text-align: right;
        }
        .warning {
          color: var(--deemphasized-text-color);
        }
        input.show-hide {
          display: none;
        }
        label.show-hide {
          cursor: pointer;
          display: block;
          min-width: 2em;
        }
        gr-diff {
          display: block;
          overflow-x: auto;
        }
        .matchingFilePath {
          color: var(--deemphasized-text-color);
        }
        .newFilePath {
          color: var(--primary-text-color);
          font-weight: var(--font-weight-medium);
        }
        .fileName {
          color: var(--link-color);
          font-weight: var(--font-weight-medium);
        }
        .truncatedFileName {
          display: none;
        }
        .mobile {
          display: none;
        }
        .reviewed {
          margin-left: var(--spacing-xxl);
          width: 15em;
        }
        .reviewedSwitch {
          color: var(--link-color);
          opacity: 0;
          justify-content: flex-end;
          width: 100%;
        }
        .reviewedSwitch:hover {
          cursor: pointer;
          opacity: 100;
        }
        .showParentButton {
          line-height: var(--line-height-normal);
          margin-bottom: calc(var(--spacing-s) * -1);
          margin-left: var(--spacing-m);
          margin-top: calc(var(--spacing-s) * -1);
        }
        .row:focus {
          outline: none;
        }
        .row:hover .reviewedSwitch,
        .row:focus-within .reviewedSwitch,
        .row.expanded .reviewedSwitch {
          opacity: 100;
        }
        .reviewedLabel {
          color: var(--deemphasized-text-color);
          margin-right: var(--spacing-l);
          opacity: 0;
        }
        .reviewedLabel.isReviewed {
          display: initial;
          opacity: 100;
        }
        .editFileControls {
          width: 7em;
        }
        .markReviewed:focus {
          outline: none;
        }
        .markReviewed,
        .pathLink {
          display: inline-block;
          margin: -2px 0;
          padding: var(--spacing-s) 0;
          text-decoration: none;
        }
        .pathLink:hover span.fullFileName,
        .pathLink:hover span.truncatedFileName {
          text-decoration: underline;
        }

        /** copy on file path **/
        .pathLink gr-copy-clipboard,
        .oldPath gr-copy-clipboard {
          display: inline-block;
          visibility: hidden;
          vertical-align: bottom;
          --gr-button-padding: 0px;
        }
        .row:focus-within gr-copy-clipboard,
        .row:hover gr-copy-clipboard {
          visibility: visible;
        }

        .file-status-arrow {
          font-size: 16px;
          position: relative;
          top: 2px;
          display: block;
        }
        .file-mode-warning {
          font-size: 16px;
          position: relative;
          top: 2px;
          color: var(--warning-foreground);
        }
        .file-mode-content {
          display: inline-block;
          color: var(--deemphasized-text-color);
        }

        @media screen and (max-width: 1200px) {
          gr-endpoint-decorator.extra-col {
            display: none;
          }
        }

        @media screen and (max-width: 1000px) {
          .reviewed {
            display: none;
          }
        }

        @media screen and (max-width: 800px) {
          .desktop {
            display: none;
          }
          .mobile {
            display: block;
          }
          .row.selected {
            background-color: var(--view-background-color);
          }
          .stats {
            display: none;
          }
          .reviewed,
          .status {
            justify-content: flex-start;
          }
          .comments {
            min-width: initial;
          }
          .expanded .fullFileName,
          .truncatedFileName {
            display: inline;
          }
          .expanded .truncatedFileName,
          .fullFileName {
            display: none;
          }
        }
        :host(.hideComments) {
          --gr-comment-thread-display: none;
        }
        :host(.hideCheckCodePointers) {
          --gr-check-code-pointers-display: none;
        }
        .checkChip {
          display: inline-flex;
          align-items: center;
          gap: var(--spacing-xs);
          border: 1px solid;
          border-radius: 999px;
          padding: var(--spacing-xxs) var(--spacing-m) var(--spacing-xxs)
            var(--spacing-s);
          vertical-align: top;
          position: relative;
          top: 2px;
          font-size: var(--font-size-small);
          font-weight: var(--font-weight-normal);
          line-height: var(--line-height-small);
          color: var(--primary-text-color);
          & gr-icon {
            font-size: var(--line-height-small);
          }
          &.info {
            border-color: var(--info-foreground);
            background-color: var(--info-background);
            & gr-icon {
              color: var(--info-foreground);
            }
          }
          &.warning {
            border-color: var(--warning-foreground);
            background-color: var(--warning-background);
            & gr-icon {
              color: var(--warning-foreground);
            }
          }
          &.error {
            border-color: var(--error-foreground);
            background-color: var(--error-background);
            & gr-icon {
              color: var(--error-foreground);
            }
          }
        }
      `,
    ];
  }

  constructor() {
    super();
    this.fileCursor.scrollMode = ScrollMode.KEEP_VISIBLE;
    this.fileCursor.cursorTargetClass = 'selected';
    this.fileCursor.focusOnMove = true;
    this.shortcutsController.addAbstract(Shortcut.LEFT_PANE, _ =>
      this.handleLeftPane()
    );
    this.shortcutsController.addAbstract(Shortcut.RIGHT_PANE, _ =>
      this.handleRightPane()
    );
    this.shortcutsController.addAbstract(Shortcut.TOGGLE_INLINE_DIFF, _ =>
      this.handleToggleInlineDiff()
    );
    this.shortcutsController.addAbstract(Shortcut.TOGGLE_ALL_INLINE_DIFFS, _ =>
      this.toggleInlineDiffs()
    );
    this.shortcutsController.addAbstract(
      Shortcut.TOGGLE_HIDE_ALL_COMMENT_THREADS_AND_CODE_POINTERS,
      _ => this.toggleHideAllCommentsAndCodePointers()
    );
    this.shortcutsController.addAbstract(
      Shortcut.TOGGLE_HIDE_CHECK_CODE_POINTERS,
      _ => this.toggleHideCheckCodePointers()
    );
    this.shortcutsController.addAbstract(
      Shortcut.CURSOR_NEXT_FILE,
      e => this.handleCursorNext(e),
      {preventDefault: false}
    );
    this.shortcutsController.addAbstract(
      Shortcut.CURSOR_PREV_FILE,
      e => this.handleCursorPrev(e),
      {preventDefault: false}
    );
    // This is already been taken care of by CURSOR_NEXT_FILE above. The two
    // shortcuts share the same bindings. It depends on whether all files
    // are expanded whether the cursor moves to the next file or line.
    this.shortcutsController.addAbstract(Shortcut.NEXT_LINE, _ => {}, {
      preventDefault: false,
    }); // docOnly
    // This is already been taken care of by CURSOR_PREV_FILE above. The two
    // shortcuts share the same bindings. It depends on whether all files
    // are expanded whether the cursor moves to the previous file or line.
    this.shortcutsController.addAbstract(Shortcut.PREV_LINE, _ => {}, {
      preventDefault: false,
    }); // docOnly
    this.shortcutsController.addAbstract(Shortcut.NEW_COMMENT, _ =>
      this.handleNewComment()
    );
    this.shortcutsController.addAbstract(Shortcut.OPEN_LAST_FILE, _ =>
      this.openSelectedFile(this.files.length - 1)
    );
    this.shortcutsController.addAbstract(Shortcut.OPEN_FIRST_FILE, _ =>
      this.openSelectedFile(0)
    );
    this.shortcutsController.addAbstract(Shortcut.OPEN_FILE, _ =>
      this.handleOpenFile()
    );
    this.shortcutsController.addAbstract(Shortcut.NEXT_CHUNK, _ =>
      this.handleNextChunk()
    );
    this.shortcutsController.addAbstract(Shortcut.PREV_CHUNK, _ =>
      this.handlePrevChunk()
    );
    this.shortcutsController.addAbstract(Shortcut.NEXT_COMMENT_THREAD, _ =>
      this.handleNextComment()
    );
    this.shortcutsController.addAbstract(Shortcut.PREV_COMMENT_THREAD, _ =>
      this.handlePrevComment()
    );
    this.shortcutsController.addAbstract(Shortcut.TOGGLE_FILE_REVIEWED, _ =>
      this.handleToggleFileReviewed()
    );
    this.shortcutsController.addAbstract(Shortcut.TOGGLE_LEFT_PANE, _ =>
      this.handleToggleLeftPane()
    );
    this.shortcutsController.addAbstract(
      Shortcut.EXPAND_ALL_COMMENT_THREADS,
      _ => {}
    ); // docOnly
    this.shortcutsController.addAbstract(
      Shortcut.COLLAPSE_ALL_COMMENT_THREADS,
      _ => {}
    ); // docOnly
    this.shortcutsController.addLocal(
      {key: Key.ENTER},
      _ => this.handleOpenFile(),
      {
        shouldSuppress: true,
      }
    );
    subscribe(
      this,
      () => this.getCommentsModel().changeComments$,
      changeComments => {
        this.changeComments = changeComments;
      }
    );
    subscribe(
      this,
      () => this.getChecksModel().allResultsSelected$,
      results => {
        this.checkResults = results;
      }
    );
    subscribe(
      this,
      () => this.getFilesModel().filesIncludingUnmodified$,
      files => {
        this.modifiedFiles = files.filter(
          f => f.status !== FileInfoStatus.UNMODIFIED
        );
        this.unmodifiedFiles = files.filter(
          f => f.status === FileInfoStatus.UNMODIFIED
        );
        this.files = [...this.modifiedFiles, ...this.unmodifiedFiles];
      }
    );
    subscribe(
      this,
      () => this.getFilesModel().filesLeftBase$,
      files => {
        this.filesLeftBase = [...files];
      }
    );
    subscribe(
      this,
      () => this.getFilesModel().filesRightBase$,
      files => {
        this.filesRightBase = [...files];
      }
    );
    subscribe(
      this,
      () => this.getBrowserModel().diffViewMode$,
      diffView => {
        this.diffViewMode = diffView;
      }
    );
    subscribe(
      this,
      () => this.getUserModel().diffPreferences$,
      diffPreferences => {
        this.diffPrefs = diffPreferences;
      }
    );
    subscribe(
      this,
      () =>
        select(
          this.getUserModel().preferences$,
          prefs => !!prefs?.size_bar_in_change_table
        ),
      sizeBarInChangeTable => {
        this.showSizeBars = sizeBarInChangeTable;
      }
    );
    subscribe(
      this,
      () => this.getUserModel().loggedIn$,
      loggedIn => {
        this.loggedIn = loggedIn;
      }
    );
    subscribe(
      this,
      () => this.getChangeModel().reviewedFiles$,
      reviewedFiles => {
        this.reviewed = reviewedFiles ?? [];
      }
    );
    subscribe(
      this,
      () => this.getChangeModel().patchNum$,
      x => (this.patchNum = x)
    );
    subscribe(
      this,
      () => this.getChangeModel().basePatchNum$,
      x => (this.basePatchNum = x)
    );
  }

  override willUpdate(changedProperties: PropertyValues): void {
    if (
      changedProperties.has('patchNum') ||
      changedProperties.has('basePatchNum')
    ) {
      this.resetFileState();
      this.collapseAllDiffs();
    }
    if (
      changedProperties.has('diffPrefs') ||
      changedProperties.has('diffViewMode')
    ) {
      this.updateDiffPreferences();
    }
    if (changedProperties.has('files')) {
      this.filesChanged();
      this.numFilesShown = Math.min(this.files.length, DEFAULT_NUM_FILES_SHOWN);
      fire(this, 'files-shown-changed', {length: this.numFilesShown});
    }
    if (changedProperties.has('expandedFiles')) {
      this.expandedFilesChanged(changedProperties.get('expandedFiles'));
    }
    if (changedProperties.has('numFilesShown')) {
      fire(this, 'files-shown-changed', {length: this.numFilesShown});
    }
  }

  override connectedCallback() {
    super.connectedCallback();

    this.getPluginLoader()
      .awaitPluginsLoaded()
      .then(() => {
        this.dynamicHeaderEndpoints =
          this.getPluginLoader().pluginEndPoints.getDynamicEndpoints(
            'change-view-file-list-header'
          );
        this.dynamicContentEndpoints =
          this.getPluginLoader().pluginEndPoints.getDynamicEndpoints(
            'change-view-file-list-content'
          );
        this.dynamicPrependedHeaderEndpoints =
          this.getPluginLoader().pluginEndPoints.getDynamicEndpoints(
            'change-view-file-list-header-prepend'
          );
        this.dynamicPrependedContentEndpoints =
          this.getPluginLoader().pluginEndPoints.getDynamicEndpoints(
            'change-view-file-list-content-prepend'
          );
        this.dynamicSummaryEndpoints =
          this.getPluginLoader().pluginEndPoints.getDynamicEndpoints(
            'change-view-file-list-summary'
          );

        if (
          this.dynamicHeaderEndpoints.length !==
          this.dynamicContentEndpoints.length
        ) {
          this.reporting.error(
            'Plugin change-view-file-list',
            new Error('dynamic header/content mismatch')
          );
        }
        if (
          this.dynamicPrependedHeaderEndpoints.length !==
          this.dynamicPrependedContentEndpoints.length
        ) {
          this.reporting.error(
            'Plugin change-view-file-list',
            new Error('dynamic prepend header/content mismatch')
          );
        }
        if (
          this.dynamicHeaderEndpoints.length !==
          this.dynamicSummaryEndpoints.length
        ) {
          this.reporting.error(
            'Plugin change-view-file-list',
            new Error('dynamic header/summary mismatch')
          );
        }
      });
    this.diffCursor = new GrDiffCursor();
    this.diffCursor.replaceDiffs(this.diffs);
  }

  override disconnectedCallback() {
    this.diffCursor?.dispose();
    this.fileCursor.unsetCursor();
    this.cancelDiffs();
    super.disconnectedCallback();
  }

  protected override async getUpdateComplete(): Promise<boolean> {
    const result = await super.getUpdateComplete();
    await Promise.all(this.diffs.map(d => d.updateComplete));
    return result;
  }

  override render() {
    this.classList.toggle('editMode', this.editMode);
    const patchChange = this.calculatePatchChange();
    return html`
      <h3 class="assistive-tech-only">File list</h3>
      ${this.renderContainer()} ${this.renderChangeTotals(patchChange)}
      ${this.renderBinaryTotals(patchChange)} ${this.renderControlRow()}
      <gr-diff-preferences-dialog id="diffPreferencesDialog">
      </gr-diff-preferences-dialog>
    `;
  }

  private renderContainer() {
    return html`
      <div
        id="container"
        @click=${(e: MouseEvent) => this.handleFileListClick(e)}
        role="grid"
        aria-label="Files list"
      >
        ${this.renderHeaderRow()} ${this.renderShownFiles()}
        ${when(this.computeShowNumCleanlyMerged(), () =>
          this.renderCleanlyMerged()
        )}
      </div>
    `;
  }

  private renderHeaderRow() {
    const showPrependedDynamicColumns =
      this.computeShowPrependedDynamicColumns();
    const showDynamicColumns = this.computeShowDynamicColumns();
    return html` <div class="header-row row" role="row">
      <!-- endpoint: change-view-file-list-header-prepend -->
      ${when(showPrependedDynamicColumns, () =>
        this.renderPrependedHeaderEndpoints()
      )}
      ${this.renderFileStatus()}
      <div class="path" role="columnheader">File</div>
      <div class="comments desktop" role="columnheader">Comments</div>
      <div class="comments mobile" role="columnheader" title="Comments">C</div>
      ${when(
        this.showSizeBars,
        () => html`<div class="sizeBars desktop" role="columnheader">Size</div>`
      )}
      <div class="header-stats" role="columnheader">Delta</div>
      <!-- endpoint: change-view-file-list-header -->
      ${when(showDynamicColumns, () => this.renderDynamicHeaderEndpoints())}
      <!-- Empty div here exists to keep spacing in sync with file rows. -->
      <div
        class="reviewed hideOnEdit"
        ?hidden=${!this.loggedIn}
        aria-hidden="true"
      ></div>
      <div class="editFileControls showOnEdit" aria-hidden="true"></div>
      <div class="show-hide" aria-hidden="true"></div>
    </div>`;
  }

  private renderPrependedHeaderEndpoints() {
    return this.dynamicPrependedHeaderEndpoints?.map(
      headerEndpoint => html`
        <gr-endpoint-decorator
          class="prepended-col"
          .name=${headerEndpoint}
          role="columnheader"
        >
          <gr-endpoint-param name="change" .value=${this.change}>
          </gr-endpoint-param>
          <gr-endpoint-param name="patchRange" .value=${this.patchRange}>
          </gr-endpoint-param>
          <gr-endpoint-param name="files" .value=${this.files}>
          </gr-endpoint-param>
        </gr-endpoint-decorator>
      `
    );
  }

  private renderDynamicHeaderEndpoints() {
    return this.dynamicHeaderEndpoints?.map(
      headerEndpoint => html`
        <gr-endpoint-decorator
          class="extra-col"
          .name=${headerEndpoint}
          role="columnheader"
        >
          <gr-endpoint-param name="change" .value=${this.change}>
          </gr-endpoint-param>
        </gr-endpoint-decorator>
      `
    );
  }

  private renderSeparator() {
    const text = 'Unmodified Files';
    const tooltipText =
      'Files not modified in this patchset, but referenced by a check or a comment.' +
      ' May include files outside of this change, also virtual or generated files.';
    return html`
      <div class="row separator-row" role="row">
        <div class="path" role="gridcell">
          <span>${text}</span>
          <gr-tooltip-content .title=${tooltipText} has-tooltip>
            <gr-icon icon="info"></gr-icon>
          </gr-tooltip-content>
        </div>
      </div>
    `;
  }

  private renderShownFiles() {
    const showDynamicColumns = this.computeShowDynamicColumns();
    const showPrependedDynamicColumns =
      this.computeShowPrependedDynamicColumns();
    const sizeBarLayout = this.computeSizeBarLayout();

    const separatorIndex = this.modifiedFiles.length;

    return incrementalRepeat({
      values: this.files,
      mapFn: (f, i) => html`
        ${when(
          i === separatorIndex &&
            this.modifiedFiles.length > 0 &&
            this.unmodifiedFiles.length > 0,
          () => this.renderSeparator()
        )}
        ${this.renderFileRow(
          f as NormalizedFileInfo,
          i,
          sizeBarLayout,
          showDynamicColumns,
          showPrependedDynamicColumns
        )}
      `,
      initialCount: this.fileListIncrement,
      targetFrameRate: 1,
      startAt: 0,
      endAt: this.numFilesShown,
    });
  }

  private renderFileRow(
    file: NormalizedFileInfo,
    index: number,
    sizeBarLayout: SizeBarLayout,
    showDynamicColumns: boolean,
    showPrependedDynamicColumns: boolean
  ) {
    const previousFileName = this.files[index - 1]?.__path;
    const patchSetFile = this.computePatchSetFile(file);
    return html` <div class="stickyArea">
      <div
        class=${`file-row row ${this.computePathClass(file.__path)}`}
        data-file=${JSON.stringify(patchSetFile)}
        tabindex="-1"
        role="row"
        aria-label=${file.__path}
      >
        <!-- endpoint: change-view-file-list-content-prepend -->
        ${when(showPrependedDynamicColumns, () =>
          this.renderPrependedContentEndpointsForFile(file)
        )}
        ${this.renderFileStatus(file)}
        ${this.renderFilePath(file, previousFileName)}
        ${this.renderFileComments(file)}
        ${this.renderSizeBar(file, sizeBarLayout)} ${this.renderFileStats(file)}
        ${when(showDynamicColumns, () =>
          this.renderDynamicContentEndpointsForFile(file)
        )}
        <!-- endpoint: change-view-file-list-content -->
        ${this.renderReviewed(file)} ${this.renderFileControls(file)}
        ${this.renderShowHide(file)}
      </div>
      ${when(
        this.isFileExpanded(file.__path),
        () => html`
          <gr-diff-host
            ?noAutoRender=${true}
            ?showLoadFailure=${true}
            .changeNum=${this.changeNum}
            .change=${this.change}
            .patchRange=${this.patchRange}
            .file=${patchSetFile}
            .path=${file.__path}
            .projectName=${this.change?.project}
            ?noRenderOnPrefsChange=${true}
          ></gr-diff-host>
        `
      )}
    </div>`;
  }

  private renderPrependedContentEndpointsForFile(file: NormalizedFileInfo) {
    return this.dynamicPrependedContentEndpoints?.map(
      contentEndpoint => html`
        <gr-endpoint-decorator
          class="prepended-col"
          .name=${contentEndpoint}
          role="gridcell"
        >
          <gr-endpoint-param name="change" .value=${this.change}>
          </gr-endpoint-param>
          <gr-endpoint-param name="changeNum" .value=${this.changeNum}>
          </gr-endpoint-param>
          <gr-endpoint-param name="patchRange" .value=${this.patchRange}>
          </gr-endpoint-param>
          <gr-endpoint-param name="path" .value=${file.__path}>
          </gr-endpoint-param>
          <gr-endpoint-param name="oldPath" .value=${this.getOldPath(file)}>
          </gr-endpoint-param>
        </gr-endpoint-decorator>
      `
    );
  }

  private renderFileStatus(file?: NormalizedFileInfo) {
    const hasExtendedStatus = this.filesLeftBase.length > 0;
    const leftStatus = this.renderFileStatusLeft(file?.__path);
    const rightStatus = this.renderFileStatusRight(file);
    return html`<div
      class=${classMap({status: true, extended: hasExtendedStatus})}
      role="gridcell"
    >
      ${leftStatus}${rightStatus}
    </div>`;
  }

  private renderDivWithTooltip(
    content: TemplateResult | string,
    tooltip: string,
    cssClass = 'content'
  ) {
    return html`
      <gr-tooltip-content title=${tooltip} has-tooltip>
        <div class=${cssClass}>${content}</div>
      </gr-tooltip-content>
    `;
  }

  private renderFileStatusRight(file?: NormalizedFileInfo) {
    const hasExtendedStatus = this.filesLeftBase.length > 0;
    // no file means "header row"
    if (!file) {
      const psNum = this.patchNum;
      return hasExtendedStatus
        ? this.renderDivWithTooltip(`${psNum}`, `Patchset ${psNum}`)
        : nothing;
    }
    if (isMagicPath(file.__path)) return nothing;

    const fileWasAlreadyChanged = this.filesLeftBase.some(
      info => info.__path === file?.__path
    );
    const fileIsReverted =
      fileWasAlreadyChanged &&
      !this.filesRightBase.some(info => info.__path === file?.__path);
    const newlyChanged = hasExtendedStatus && !fileWasAlreadyChanged;

    const status = fileIsReverted
      ? FileInfoStatus.REVERTED
      : file?.status ?? FileInfoStatus.MODIFIED;
    const left = `patchset ${this.basePatchNum}`;
    const right = `patchset ${this.patchNum}`;
    const postfix = ` between ${left} and ${right}`;

    return html`<gr-file-status
      .status=${status}
      .labelPostfix=${postfix}
      ?newlyChanged=${newlyChanged}
    ></gr-file-status>`;
  }

  private renderFileStatusLeft(path?: string) {
    if (this.filesLeftBase.length === 0) return nothing;
    const arrow = html`
      <gr-icon
        icon="arrow_right_alt"
        class="file-status-arrow"
        aria-label="then"
      ></gr-icon>
    `;
    // no path means "header row"
    const psNum = this.basePatchNum;
    if (!path) {
      return html`
        ${this.renderDivWithTooltip(`${psNum}`, `Patchset ${psNum}`)} ${arrow}
      `;
    }
    if (isMagicPath(path)) return nothing;
    const file = this.filesLeftBase.find(info => info.__path === path);
    if (!file) return nothing;

    const status = file.status ?? FileInfoStatus.MODIFIED;
    const left = 'base';
    const right = `patchset ${this.basePatchNum}`;
    const postfix = ` between ${left} and ${right}`;

    return html`
      <gr-file-status
        .status=${status}
        .labelPostfix=${postfix}
      ></gr-file-status>
      ${arrow}
    `;
  }

  private renderFilePath(file: NormalizedFileInfo, previousFilePath?: string) {
    return html`
      <span class="path" role="gridcell">
        <a class="pathLink" href=${ifDefined(this.computeDiffURL(file.__path))}>
          <span title=${computeDisplayPath(file.__path)} class="fullFileName">
            ${this.renderStyledPath(file.__path, previousFilePath)}
          </span>
          <span
            title=${computeDisplayPath(file.__path)}
            class="truncatedFileName"
          >
            ${computeTruncatedPath(file.__path)}
          </span>
          ${this.renderFileMode(file)}
          <gr-copy-clipboard
            ?hideInput=${true}
            .text=${file.__path}
          ></gr-copy-clipboard>
        </a>
        ${when(
          file.old_path,
          () => html`
            <div class="oldPath" title=${ifDefined(file.old_path)}>
              ${file.old_path}
              <gr-copy-clipboard
                ?hideInput=${true}
                .text=${file.old_path}
              ></gr-copy-clipboard>
            </div>
          `
        )}
      </span>
    `;
  }

  private renderFileMode(file: NormalizedFileInfo) {
    const {old_mode, new_mode} = file;

    // For added, modified or deleted regular files we do not want to render
    // anything. Only if a file changed from something else to regular, then let
    // the user know.
    if (new_mode === undefined) return nothing;
    let newModeStr = fileModeToString(new_mode, false);
    if (new_mode === FileMode.REGULAR_FILE) {
      if (old_mode === undefined) return nothing;
      if (old_mode === FileMode.REGULAR_FILE) return nothing;
      newModeStr = `non-${fileModeToString(old_mode, false)}`;
    }

    const changed = old_mode !== undefined && old_mode !== new_mode;
    const icon = changed
      ? html`<gr-icon icon="warning" class="file-mode-warning"></gr-icon> `
      : '';
    const action = changed
      ? `changed from ${fileModeToString(old_mode)} to`
      : 'is';
    return this.renderDivWithTooltip(
      html`${icon}(${newModeStr})`,
      `file mode ${action} ${fileModeToString(new_mode)}`,
      'file-mode-content'
    );
  }

  private renderStyledPath(filePath: string, previousFilePath?: string) {
    const {matchingFolders, newFolders, fileName} = diffFilePaths(
      filePath,
      previousFilePath
    );
    return [
      matchingFolders.length > 0
        ? html`<span class="matchingFilePath">${matchingFolders}</span>`
        : nothing,
      newFolders.length > 0
        ? html`<span class="newFilePath">${newFolders}</span>`
        : nothing,
      html`<span class="fileName">${fileName}</span>`,
    ];
  }

  private renderFileComments(file: NormalizedFileInfo) {
    return html` <div role="gridcell">
      <div class="comments desktop">
        <span>${this.renderCommentsChips(file)}</span>
        <span>${this.renderChecksChips(file)}</span>
        <span class="noCommentsScreenReaderText">
          <!-- Screen readers read the following content only if 2 other
          spans in the parent div is empty. The content is not visible on
          the page.
          Without this span, screen readers don't navigate correctly inside
          table, because empty div doesn't rendered. For example, VoiceOver
          jumps back to the whole table.
          We can use &nbsp instead, but it sounds worse.
          -->
          No comments
        </span>
      </div>
      <div class="comments mobile">
        <span class="drafts">${this.computeDraftsStringMobile(file)}</span>
        <span>${this.computeCommentsStringMobile(file)}</span>
        <span class="noCommentsScreenReaderText">
          <!-- The same as for desktop comments -->
          No comments
        </span>
      </div>
    </div>`;
  }

  private renderSizeBar(
    file: NormalizedFileInfo,
    sizeBarLayout: SizeBarLayout
  ) {
    return html` <div class="desktop" role="gridcell">
      <!-- The content must be in a separate div. It guarantees, that
          gridcell always visible for screen readers.
          For example, without a nested div screen readers pronounce the
          "Commit message" row content with incorrect column headers.
        -->
      <div class=${this.computeSizeBarsClass(file.__path)} aria-hidden="true">
        <svg width="61" height="8">
          <rect
            x=${this.computeBarDeletionX(file, sizeBarLayout)}
            y="0"
            height="8"
            fill="var(--negative-red-text-color)"
            width=${this.computeBarDeletionWidth(file, sizeBarLayout)}
          ></rect>
          <rect
            x=${this.computeBarAdditionX(sizeBarLayout)}
            y="0"
            height="8"
            fill="var(--positive-green-text-color)"
            width=${this.computeBarAdditionWidth(file, sizeBarLayout)}
          ></rect>
        </svg>
      </div>
    </div>`;
  }

  private renderFileStats(file: NormalizedFileInfo) {
    return html` <div class="stats" role="gridcell">
      <!-- The content must be in a separate div. It guarantees, that
        gridcell always visible for screen readers.
        For example, without a nested div screen readers pronounce the
        "Commit message" row content with incorrect column headers.
        -->
      <div class=${this.computeClass('', file.__path)}>
        <span
          class="removed"
          tabindex="0"
          aria-label=${`${file.lines_deleted} removed`}
          ?hidden=${!!file.binary}
        >
          -${file.lines_deleted}
        </span>
        <span
          class="added"
          tabindex="0"
          aria-label=${`${file.lines_inserted} added`}
          ?hidden=${!!file.binary}
        >
          +${file.lines_inserted}
        </span>
        <span
          class=${ifDefined(this.computeBinaryClass(file.size_delta))}
          ?hidden=${!file.binary}
        >
          ${formatBytes(file.size_delta)}
          ${this.formatPercentage(file.size, file.size_delta)}
        </span>
      </div>
    </div>`;
  }

  private renderDynamicContentEndpointsForFile(file: NormalizedFileInfo) {
    return this.dynamicContentEndpoints?.map(
      contentEndpoint => html` <div
        class=${this.computeClass('', file.__path)}
        role="gridcell"
      >
        <gr-endpoint-decorator class="extra-col" .name=${contentEndpoint}>
          <gr-endpoint-param name="change" .value=${this.change}>
          </gr-endpoint-param>
          <gr-endpoint-param name="changeNum" .value=${this.changeNum}>
          </gr-endpoint-param>
          <gr-endpoint-param name="patchRange" .value=${this.patchRange}>
          </gr-endpoint-param>
          <gr-endpoint-param name="path" .value=${file.__path}>
          </gr-endpoint-param>
        </gr-endpoint-decorator>
      </div>`
    );
  }

  private renderReviewed(file: NormalizedFileInfo) {
    if (!this.loggedIn) return nothing;
    const isReviewed = this.reviewed.includes(file.__path);
    const reviewedTitle = `Mark as ${
      isReviewed ? 'not ' : ''
    }reviewed (shortcut: r)`;
    const reviewedText = isReviewed ? 'MARK UNREVIEWED' : 'MARK REVIEWED';
    return html` <div class="reviewed hideOnEdit" role="gridcell">
      <span
        class=${`reviewedLabel ${isReviewed ? 'isReviewed' : ''}`}
        aria-hidden=${this.booleanToString(!isReviewed)}
        >Reviewed</span
      >
      <!-- Do not use input type="checkbox" with hidden input and
              visible label here. Screen readers don't read/interract
              correctly with such input.
          -->
      <span
        class="reviewedSwitch"
        role="switch"
        tabindex="0"
        @click=${(e: MouseEvent) => this.reviewedClick(e)}
        @keydown=${(e: KeyboardEvent) => this.reviewedClick(e)}
        aria-label="Reviewed"
        aria-checked=${this.booleanToString(isReviewed)}
      >
        <!-- Trick with tabindex to avoid outline on mouse focus, but
            preserve focus outline for keyboard navigation -->
        <span tabindex="-1" class="markReviewed" title=${reviewedTitle}
          >${reviewedText}</span
        >
      </span>
    </div>`;
  }

  private renderFileControls(file: NormalizedFileInfo) {
    return html` <div
      class="editFileControls showOnEdit"
      role="gridcell"
      aria-hidden=${this.booleanToString(!this.editMode)}
    >
      ${when(
        this.editMode,
        () => html`
          <gr-edit-file-controls
            class=${this.computeClass(
              '',
              file.__path,
              /* showForCommitMessage */ true
            )}
            .filePath=${file.__path}
          ></gr-edit-file-controls>
        `
      )}
    </div>`;
  }

  private renderShowHide(file: NormalizedFileInfo) {
    const expanded = this.isFileExpanded(file.__path);
    return html` <div class="show-hide" role="gridcell">
      <!-- Do not use input type="checkbox" with hidden input and
            visible label here. Screen readers don't read/interract
            correctly with such input.
        -->
      <span
        class="show-hide"
        data-path=${file.__path}
        data-expand="true"
        role="switch"
        tabindex="0"
        aria-checked=${this.isFileExpandedStr(file.__path)}
        aria-label=${expanded ? 'collapse' : 'expand'}
        aria-description=${expanded
          ? 'Collapse diff of this file'
          : 'Expand diff of this file'}
        @click=${this.expandedClick}
        @keydown=${this.expandedClick}
      >
        <!-- Trick with tabindex to avoid outline on mouse focus, but
          preserve focus outline for keyboard navigation -->
        <gr-icon
          class="show-hide-icon"
          tabindex="-1"
          id="icon"
          icon=${expanded ? 'expand_less' : 'expand_more'}
        ></gr-icon>
      </span>
    </div>`;
  }

  private renderCleanlyMerged() {
    const showPrependedDynamicColumns =
      this.computeShowPrependedDynamicColumns();
    return html` <div class="row">
      <!-- endpoint: change-view-file-list-content-prepend -->
      ${when(showPrependedDynamicColumns, () =>
        this.renderPrependedContentEndpoints()
      )}
      <div role="gridcell">
        <div>
          <span class="cleanlyMergedText">
            ${this.computeCleanlyMergedText()}
          </span>
          <gr-button
            link
            class="showParentButton"
            @click=${this.handleShowParent1}
          >
            Show Parent 1
          </gr-button>
        </div>
      </div>
    </div>`;
  }

  private renderPrependedContentEndpoints() {
    return this.dynamicPrependedContentEndpoints?.map(
      contentEndpoint => html`
        <gr-endpoint-decorator
          class="prepended-col"
          .name=${contentEndpoint}
          role="gridcell"
        >
          <gr-endpoint-param name="change" .value=${this.change}>
          </gr-endpoint-param>
          <gr-endpoint-param name="changeNum" .value=${this.changeNum}>
          </gr-endpoint-param>
          <gr-endpoint-param name="patchRange" .value=${this.patchRange}>
          </gr-endpoint-param>
          <gr-endpoint-param
            name="cleanlyMergedPaths"
            .value=${this.cleanlyMergedPaths}
          >
          </gr-endpoint-param>
          <gr-endpoint-param
            name="cleanlyMergedOldPaths"
            .value=${this.cleanlyMergedOldPaths}
          >
          </gr-endpoint-param>
        </gr-endpoint-decorator>
      `
    );
  }

  private renderChangeTotals(patchChange: PatchChange) {
    const showDynamicColumns = this.computeShowDynamicColumns();
    if (this.shouldHideChangeTotals(patchChange)) return nothing;
    return html`
      <div class="row totalChanges">
        <div class="total-stats">
          <div>
            <span
              class="removed"
              tabindex="0"
              aria-label="Total ${patchChange.deleted} lines removed"
            >
              -${patchChange.deleted}
            </span>
            <span
              class="added"
              tabindex="0"
              aria-label="Total ${patchChange.inserted} lines added"
            >
              +${patchChange.inserted}
            </span>
          </div>
        </div>
        ${when(showDynamicColumns, () =>
          this.dynamicSummaryEndpoints?.map(
            summaryEndpoint => html`
              <gr-endpoint-decorator class="extra-col" name=${summaryEndpoint}>
                <gr-endpoint-param name="change" .value=${this.change}>
                </gr-endpoint-param>
                <gr-endpoint-param name="patchRange" .value=${this.patchRange}>
                </gr-endpoint-param>
              </gr-endpoint-decorator>
            `
          )
        )}

        <!-- Empty div here exists to keep spacing in sync with file rows. -->
        <div class="reviewed hideOnEdit" ?hidden=${!this.loggedIn}></div>
        <div class="editFileControls showOnEdit"></div>
        <div class="show-hide"></div>
      </div>
    `;
  }

  private renderBinaryTotals(patchChange: PatchChange) {
    if (this.shouldHideBinaryChangeTotals(patchChange)) return nothing;
    const deltaInserted = formatBytes(patchChange.size_delta_inserted);
    const deltaDeleted = formatBytes(patchChange.size_delta_deleted);
    return html`
      <div class="row totalChanges">
        <div class="total-stats">
          <span
            class="removed"
            aria-label="Total bytes removed: ${deltaDeleted}"
          >
            ${deltaDeleted}
            ${this.formatPercentage(
              patchChange.total_size,
              patchChange.size_delta_deleted
            )}
          </span>
          <span
            class="added"
            aria-label="Total bytes inserted: ${deltaInserted}"
          >
            ${deltaInserted}
            ${this.formatPercentage(
              patchChange.total_size,
              patchChange.size_delta_inserted
            )}
          </span>
        </div>
      </div>
    `;
  }

  private renderControlRow() {
    return html`<div
      class=${`row controlRow ${this.computeFileListControlClass()}`}
    >
      <gr-button
        class="fileListButton"
        id="incrementButton"
        link=""
        @click=${this.incrementNumFilesShown}
      >
        ${this.computeIncrementText()}
      </gr-button>
      <gr-tooltip-content
        ?has-tooltip=${this.computeWarnShowAll()}
        ?show-icon=${this.computeWarnShowAll()}
        .title=${this.computeShowAllWarning()}
      >
        <gr-button
          class="fileListButton"
          id="showAllButton"
          link=""
          @click=${this.showAllFiles}
        >
          ${this.computeShowAllText()}
        </gr-button>
      </gr-tooltip-content>
    </div>`;
  }

  renderCommentsChips(file?: NormalizedFileInfo) {
    if (!this.changeComments || !this.patchRange || !file?.__path) {
      return nothing;
    }
    const commentThreads = this.changeComments?.computeCommentsThreads(
      this.patchRange,
      file.__path,
      file
    );
    const draftCount = this.changeComments?.computeDraftCountForFile(
      this.patchRange,
      file
    );
    return html`<gr-comments-summary
      .commentThreads=${commentThreads}
      .draftCount=${draftCount}
      emptyWhenNoComments
    ></gr-comments-summary>`;
  }

  renderChecksChips(file?: NormalizedFileInfo) {
    if (!this.checkResults || !this.patchRange || !file?.__path) {
      return nothing;
    }

    const iconsByName: Record<string, ChecksIcon[]> = {};

    // Check both current and old file paths
    const pathsToCheck = [file.__path];
    if (file.old_path) {
      pathsToCheck.push(file.old_path);
    }

    const latestResults =
      this.checkResults?.filter(result => result.isLatestAttempt) ?? [];

    for (const result of latestResults) {
      if (
        result.codePointers === undefined ||
        !result.codePointers.some(pointer =>
          pathsToCheck.includes(pointer.path)
        )
      ) {
        continue;
      }
      const icon = iconFor(result.category);
      iconsByName[icon.name] ??= [];
      iconsByName[icon.name].push(icon);
    }

    return Object.values(iconsByName).map(
      icons =>
        html`
          <div class="checkChip ${icons[0].name}">
            <gr-icon
              icon=${icons[0].name}
              ?filled=${!!icons[0].filled}
            ></gr-icon>
            <div>${icons.length}</div>
          </div>
        `
    );
  }

  protected override firstUpdated(): void {
    this.detectChromiteButler();
    this.reporting.fileListDisplayed();
  }

  // TODO: Move into files-model.
  // visible for testing
  async updateCleanlyMergedPaths() {
    // When viewing Auto Merge base vs a patchset, add an additional row that
    // knows how many files were cleanly merged. This requires an additional RPC
    // for the diffs between target parent and the patch set. The cleanly merged
    // files are all the files in the target RPC that weren't in the Auto Merge
    // RPC.
    if (
      this.change &&
      this.changeNum &&
      this.patchNum &&
      new RevisionInfo(this.change).isMergeCommit(this.patchNum) &&
      this.basePatchNum === PARENT &&
      this.patchNum !== EDIT
    ) {
      const allFilesByPath = await this.restApiService.getChangeOrEditFiles(
        this.changeNum,
        {
          basePatchNum: -1 as BasePatchSetNum, // -1 is first (target) parent
          patchNum: this.patchNum,
        }
      );
      if (!allFilesByPath) return;
      const conflictingPaths = this.files.map(f => f.__path);
      this.cleanlyMergedPaths = Object.keys(allFilesByPath).filter(
        path => !conflictingPaths.includes(path)
      );
      this.cleanlyMergedOldPaths = this.cleanlyMergedPaths
        .map(path => allFilesByPath[path].old_path)
        .filter((oldPath): oldPath is string => !!oldPath);
    } else {
      this.cleanlyMergedPaths = [];
      this.cleanlyMergedOldPaths = [];
    }
  }

  private detectChromiteButler() {
    const hasButler = !!document.getElementById('butler-suggested-owners');
    if (hasButler) {
      this.reporting.reportExtension('butler');
    }
  }

  get diffs(): GrDiffHost[] {
    const diffs = this.shadowRoot!.querySelectorAll('gr-diff-host');
    // It is possible that a bogus diff element is hanging around invisibly
    // from earlier with a different patch set choice and associated with a
    // different entry in the files array. So filter on visible items only.
    return Array.from(diffs).filter(
      el => !!el && !!el.style && el.style.display !== 'none'
    );
  }

  resetFileState() {
    this.numFilesShown = DEFAULT_NUM_FILES_SHOWN;
    this.selectedIndex = 0;
    this.fileCursor.setCursorAtIndex(this.selectedIndex, true);
  }

  openDiffPrefs() {
    this.diffPreferencesDialog?.open();
  }

  // Private but used in tests.
  calculatePatchChange(): PatchChange {
    const magicFilesExcluded = this.files.filter(
      file => !isMagicPath(file.__path)
    );

    return magicFilesExcluded.reduce((acc, obj) => {
      const inserted = obj.lines_inserted ? obj.lines_inserted : 0;
      const deleted = obj.lines_deleted ? obj.lines_deleted : 0;
      const total_size = obj.size && obj.binary ? obj.size : 0;
      const size_delta_inserted =
        obj.binary && obj.size_delta && obj.size_delta > 0 ? obj.size_delta : 0;
      const size_delta_deleted =
        obj.binary && obj.size_delta && obj.size_delta < 0 ? obj.size_delta : 0;

      return {
        inserted: acc.inserted + inserted,
        deleted: acc.deleted + deleted,
        size_delta_inserted: acc.size_delta_inserted + size_delta_inserted,
        size_delta_deleted: acc.size_delta_deleted + size_delta_deleted,
        total_size: acc.total_size + total_size,
      };
    }, createDefaultPatchChange());
  }

  private toggleHideAllCommentsAndCodePointers() {
    const hideComments = this.classList.toggle('hideComments');
    if (hideComments) {
      this.classList.add('hideCheckCodePointers');
    } else {
      this.classList.remove('hideCheckCodePointers');
    }
  }

  toggleHideCheckCodePointers() {
    this.classList.toggle('hideCheckCodePointers');
  }

  // private but used in test
  toggleFileExpanded(file: PatchSetFile) {
    // Is the path in the list of expanded diffs? If so, remove it, otherwise
    // add it to the list.
    const indexInExpanded = this.expandedFiles.findIndex(
      f => f.path === file.path
    );
    if (indexInExpanded === -1) {
      this.reporting.reportInteraction(Interaction.FILE_LIST_DIFF_EXPANDED);
      this.expandedFiles = this.expandedFiles.concat([file]);
    } else {
      this.reporting.reportInteraction(Interaction.FILE_LIST_DIFF_COLLAPSED);
      this.expandedFiles = this.expandedFiles.filter(
        (_val, idx) => idx !== indexInExpanded
      );
    }
    const indexInAll = this.files.findIndex(f => f.__path === file.path);
    this.shadowRoot!.querySelectorAll(`.${FILE_ROW_CLASS}`)[
      indexInAll
    ].scrollIntoView({block: 'nearest'});
  }

  private toggleFileExpandedByIndex(index: number) {
    this.toggleFileExpanded(this.computePatchSetFile(this.files[index]));
  }

  // Private but used in tests.
  updateDiffPreferences() {
    if (!this.diffs.length) {
      return;
    }
    // Re-render all expanded diffs sequentially.
    this.renderInOrder(this.expandedFiles, this.diffs);
  }

  expandAllDiffs() {
    const newFiles = this.files
      .slice(0, this.numFilesShown)
      // TODO(b/419187980): Refactor expandedFiles to use a Set for efficiency.
      .filter(file => !this.expandedFiles.some(f => f.path === file.__path))
      .map(file => this.computePatchSetFile(file));

    this.reporting.reportInteraction(Interaction.FILE_LIST_ALL_DIFFS_EXPANDED);
    this.expandedFiles = newFiles.concat(this.expandedFiles);
  }

  collapseAllDiffs() {
    this.reporting.reportInteraction(Interaction.FILE_LIST_ALL_DIFFS_COLLAPSED);
    this.expandedFiles = [];
  }

  /**
   * Computes a shortened string with the number of drafts.
   * Private but used in tests.
   */
  computeDraftsStringMobile(file?: NormalizedFileInfo) {
    if (this.changeComments === undefined) return '';
    const draftCount = this.changeComments.computeDraftCountForFile(
      this.patchRange,
      file
    );
    return draftCount === 0 ? '' : `${draftCount}d`;
  }

  /**
   * Computes a shortened string with the number of comments.
   */
  computeCommentsStringMobile(file?: NormalizedFileInfo) {
    if (
      this.changeComments === undefined ||
      this.patchRange === undefined ||
      file === undefined
    ) {
      return '';
    }
    const commentThreadCount =
      this.changeComments.computeCommentThreads({
        patchNum: this.patchRange.basePatchNum,
        path: file.__path,
      }).length +
      this.changeComments.computeCommentThreads({
        patchNum: this.patchRange.patchNum,
        path: file.__path,
      }).length;
    return commentThreadCount === 0 ? '' : `${commentThreadCount}c`;
  }

  // Private but used in tests.
  reviewFile(path: string, reviewed?: boolean) {
    if (this.editMode) return Promise.resolve();
    reviewed = reviewed ?? !this.reviewed.includes(path);
    return this._saveReviewedState(path, reviewed);
  }

  _saveReviewedState(path: string, reviewed: boolean) {
    assertIsDefined(this.changeNum, 'changeNum');
    assertIsDefined(this.patchRange, 'patchRange');

    return this.getChangeModel().setReviewedFilesStatus(
      this.changeNum,
      this.patchRange.patchNum,
      path,
      reviewed
    );
  }

  /**
   * Returns true if the event e is a click on an element.
   *
   * The click is: mouse click or pressing Enter or Space key
   * P.S> Screen readers sends click event as well
   */
  private isClickEvent(e: MouseEvent | KeyboardEvent) {
    if (e.type === 'click') {
      return true;
    }
    const ke = e as KeyboardEvent;
    const isSpaceOrEnter = ke.key === 'Enter' || ke.key === ' ';
    return ke.type === 'keydown' && isSpaceOrEnter;
  }

  private fileActionClick(
    e: MouseEvent | KeyboardEvent,
    fileAction: (file: PatchSetFile) => void
  ) {
    if (this.isClickEvent(e)) {
      const fileRow = this.getFileRowFromEvent(e);
      if (!fileRow) {
        return;
      }
      // Prevent default actions (e.g. scrolling for space key)
      e.preventDefault();
      // Prevent handleFileListClick handler call
      e.stopPropagation();
      this.fileCursor.setCursor(fileRow.element);
      fileAction(fileRow.file);
    }
  }

  // Private but used in tests.
  reviewedClick(e: MouseEvent | KeyboardEvent) {
    this.fileActionClick(e, file => this.reviewFile(file.path));
  }

  private expandedClick(e: MouseEvent | KeyboardEvent) {
    this.fileActionClick(e, file => this.toggleFileExpanded(file));
  }

  /**
   * Handle all events from the file list dom-repeat so event handlers don't
   * have to get registered for potentially very long lists.
   * Private but used in tests.
   */
  handleFileListClick(e: MouseEvent) {
    if (!e.target) {
      return;
    }
    const fileRow = this.getFileRowFromEvent(e);
    if (!fileRow) {
      return;
    }
    const file = fileRow.file;
    const path = file.path;
    // If a path cannot be interpreted from the click target (meaning it's not
    // somewhere in the row, e.g. diff content) or if the user clicked the
    // link, defer to the native behavior.
    if (!path || descendedFromClass(e.target as Element, 'pathLink')) {
      return;
    }

    // Disregard the event if the click target is in the edit controls.
    if (descendedFromClass(e.target as Element, 'editFileControls')) {
      return;
    }

    e.preventDefault();
    this.fileCursor.setCursor(fileRow.element);
    this.toggleFileExpanded(file);
  }

  private getFileRowFromEvent(e: Event): FileRow | null {
    // Traverse upwards to find the row element if the target is not the row.
    let row = e.target as HTMLElement;
    while (!row.classList.contains(FILE_ROW_CLASS) && row.parentElement) {
      row = row.parentElement;
    }

    // No action needed for item without a valid file
    if (!row.dataset['file']) {
      return null;
    }

    return {
      file: JSON.parse(row.dataset['file']) as PatchSetFile,
      element: row,
    };
  }

  /**
   * Generates file range from file info object.
   */
  private computePatchSetFile(file: NormalizedFileInfo): PatchSetFile {
    const fileData: PatchSetFile = {
      path: file.__path,
    };
    if (file.old_path) {
      fileData.basePath = file.old_path;
    }
    return fileData;
  }

  private handleLeftPane() {
    if (this.noDiffsExpanded()) return;
    this.diffCursor?.moveLeft();
  }

  private handleRightPane() {
    if (this.noDiffsExpanded()) return;
    this.diffCursor?.moveRight();
  }

  private handleToggleInlineDiff() {
    if (this.fileCursor.index === -1) return;
    this.toggleFileExpandedByIndex(this.fileCursor.index);
  }

  // Private but used in tests.
  handleCursorNext(e: KeyboardEvent) {
    // We want to allow users to use arrow keys for standard browser scrolling
    // when files are not expanded. That is also why we use the `preventDefault`
    // option when registering the shortcut.
    if (this.filesExpanded !== FilesExpandedState.ALL && e.key === Key.DOWN) {
      return;
    }

    e.preventDefault();
    e.stopPropagation();
    if (this.filesExpanded === FilesExpandedState.ALL) {
      this.diffCursor?.moveDown();
    } else {
      this.fileCursor.next({circular: true});
      this.selectedIndex = this.fileCursor.index;
    }
  }

  // Private but used in tests.
  handleCursorPrev(e: KeyboardEvent) {
    // We want to allow users to use arrow keys for standard browser scrolling
    // when files are not expanded. That is also why we use the `preventDefault`
    // option when registering the shortcut.
    if (this.filesExpanded !== FilesExpandedState.ALL && e.key === Key.UP) {
      return;
    }

    e.preventDefault();
    e.stopPropagation();
    if (this.filesExpanded === FilesExpandedState.ALL) {
      this.diffCursor?.moveUp();
    } else {
      this.fileCursor.previous({circular: true});
      this.selectedIndex = this.fileCursor.index;
    }
  }

  private handleNewComment() {
    this.classList.remove('hideComments');
    this.diffCursor?.createCommentInPlace();
  }

  // Private but used in tests.
  handleOpenFile() {
    if (this.filesExpanded === FilesExpandedState.ALL) {
      this.openCursorFile();
      return;
    }
    this.openSelectedFile();
  }

  private handleNextChunk() {
    if (this.noDiffsExpanded()) return;
    this.diffCursor?.moveToNextChunk();
  }

  private handleNextComment() {
    if (this.noDiffsExpanded()) return;
    this.diffCursor?.moveToNextCommentThread();
  }

  private handlePrevChunk() {
    if (this.noDiffsExpanded()) return;
    this.diffCursor?.moveToPreviousChunk();
  }

  private handlePrevComment() {
    if (this.noDiffsExpanded()) return;
    this.diffCursor?.moveToPreviousCommentThread();
  }

  private handleToggleFileReviewed() {
    if (!this.files[this.fileCursor.index]) {
      return;
    }
    this.reviewFile(this.files[this.fileCursor.index].__path);
  }

  private handleToggleLeftPane() {
    this.diffs.forEach(diff => {
      diff.toggleLeftDiff();
    });
  }

  private toggleInlineDiffs() {
    if (this.filesExpanded === FilesExpandedState.ALL) {
      this.collapseAllDiffs();
    } else {
      this.expandAllDiffs();
    }
  }

  // Private but used in tests.
  openCursorFile() {
    const diff = this.diffCursor?.getTargetDiffElement();
    if (!this.change || !diff || !this.patchNum || !diff.path) {
      throw new Error('change, diff and pacthNum must be all set and valid');
    }
    this.getNavigation().setUrl(
      this.getViewModel().diffUrl({
        diffView: {path: diff.path},
        patchNum: this.patchNum,
      })
    );
  }

  // Private but used in tests.
  openSelectedFile(index?: number) {
    if (index !== undefined) {
      this.fileCursor.setCursorAtIndex(index);
    }
    if (!this.files[this.fileCursor.index]) {
      return;
    }
    if (!this.change || !this.patchNum) {
      throw new Error('change and patchRange must be set');
    }
    this.getNavigation().setUrl(
      this.getViewModel().diffUrl({
        diffView: {path: this.files[this.fileCursor.index].__path},
        patchNum: this.patchNum,
      })
    );
  }

  // Private but used in tests.
  shouldHideChangeTotals(patchChange: PatchChange): boolean {
    return patchChange.inserted === 0 && patchChange.deleted === 0;
  }

  // Private but used in tests.
  shouldHideBinaryChangeTotals(patchChange: PatchChange) {
    return (
      patchChange.size_delta_inserted === 0 &&
      patchChange.size_delta_deleted === 0
    );
  }

  /** Returns an edit or diff URL depending on `editMode`. */
  // Private but used in tests
  computeDiffURL(path?: string): string | undefined {
    if (path === undefined) return;
    if (this.patchNum === undefined) return;

    if (this.editMode && path !== SpecialFilePath.MERGE_LIST) {
      return this.getViewModel().editUrl({
        patchNum: this.patchNum,
        editView: {path},
      });
    }
    return this.getViewModel().diffUrl({
      diffView: {path},
      patchNum: this.patchNum,
    });
  }

  // Private but used in tests.
  formatPercentage(size?: number, delta?: number) {
    if (size === undefined || delta === undefined) {
      return '';
    }
    const oldSize = size - delta;

    if (oldSize === 0) {
      return '';
    }

    const percentage = Math.round(Math.abs((delta * 100) / oldSize));
    return `(${delta > 0 ? '+' : '-'}${percentage}%)`;
  }

  private computeBinaryClass(delta?: number) {
    if (!delta) {
      return;
    }
    return delta > 0 ? 'added' : 'removed';
  }

  // Private but used in tests.
  computeClass(baseClass = '', path?: string, showForCommitMessage = false) {
    const classes = [baseClass];
    if (
      !(showForCommitMessage && path === SpecialFilePath.COMMIT_MESSAGE) &&
      isMagicPath(path)
    ) {
      classes.push('invisible');
    }

    return classes.join(' ').trim();
  }

  private computePathClass(path: string | undefined) {
    return this.isFileExpanded(path) ? 'expanded' : '';
  }

  private computeShowNumCleanlyMerged(): boolean {
    return this.cleanlyMergedPaths.length > 0;
  }

  private computeCleanlyMergedText(): string {
    const fileCount = pluralize(this.cleanlyMergedPaths.length, 'file');
    return `${fileCount} merged cleanly in Parent 1`;
  }

  private handleShowParent1(): void {
    if (!this.change || !this.patchRange) return;
    this.getNavigation().setUrl(
      createChangeUrl({
        change: this.change,
        patchNum: this.patchRange.patchNum,
        basePatchNum: -1 as BasePatchSetNum, // Parent 1
      })
    );
  }

  // Private but used in tests.
  updateDiffCursor() {
    // Overwrite the cursor's list of diffs:
    this.diffCursor?.replaceDiffs(this.diffs);
  }

  async filesChanged() {
    if (this.expandedFiles.length > 0) this.expandedFiles = [];
    await this.updateCleanlyMergedPaths();
    if (!this.files || this.files.length === 0) return;
    await this.updateComplete;
    this.fileCursor.stops = Array.from(
      this.shadowRoot?.querySelectorAll(`.${FILE_ROW_CLASS}`) ?? []
    );
    this.fileCursor.setCursorAtIndex(this.selectedIndex, true);
  }

  private incrementNumFilesShown() {
    this.numFilesShown += this.fileListIncrement;
    if (this.numFilesShown > this.files.length) {
      this.numFilesShown = this.files.length;
    }
  }

  private computeFileListControlClass() {
    return this.numFilesShown >= this.files.length ? 'invisible' : '';
  }

  private computeIncrementText() {
    const text = Math.min(
      this.fileListIncrement,
      this.files.length - this.numFilesShown
    );
    return `Show ${text} More`;
  }

  // Private but used in tests.
  computeShowAllText() {
    // Exclude commit message from total count since it's not a real file
    const fileCount = this.files.filter(
      f => f.__path !== SpecialFilePath.COMMIT_MESSAGE
    ).length;
    return `Show All ${fileCount} Files`;
  }

  private computeWarnShowAll() {
    return this.files.length > WARN_SHOW_ALL_THRESHOLD;
  }

  private computeShowAllWarning() {
    if (!this.computeWarnShowAll()) {
      return '';
    }
    return `Warning: showing all ${this.files.length} files may take several seconds.`;
  }

  private showAllFiles() {
    this.numFilesShown = this.files.length;
  }

  /**
   * Converts any boolean-like variable to the string 'true' or 'false'
   *
   * This method is useful when you bind aria-checked attribute to a boolean
   * value. The aria-checked attribute is string attribute. Binding directly
   * to boolean variable causes problem on gerrit-CI.
   *
   * @return 'true' if val is true-like, otherwise false
   */
  private booleanToString(val?: unknown) {
    return val ? 'true' : 'false';
  }

  private isFileExpanded(path: string | undefined) {
    return this.expandedFiles.some(f => f.path === path);
  }

  private isFileExpandedStr(path: string | undefined) {
    return this.booleanToString(this.isFileExpanded(path));
  }

  private computeExpandedFiles(): FilesExpandedState {
    if (this.expandedFiles.length === 0) {
      return FilesExpandedState.NONE;
    } else if (this.expandedFiles.length === this.files.length) {
      return FilesExpandedState.ALL;
    }
    return FilesExpandedState.SOME;
  }

  /**
   * Handle splices to the list of expanded file paths. If there are any new
   * entries in the expanded list, then render each diff corresponding in
   * order by waiting for the previous diff to finish before starting the next
   * one.
   *
   * @param newFiles The new files that have been added.
   * Private but used in tests.
   */
  async expandedFilesChanged(oldFiles: Array<PatchSetFile>) {
    this.filesExpanded = this.computeExpandedFiles();

    const newFiles = this.expandedFiles.filter(
      file => (oldFiles ?? []).findIndex(f => f.path === file.path) === -1
    );

    // Required so that the newly created diff view is included in this.diffs.
    await this.updateComplete;

    if (newFiles.length) {
      await this.renderInOrder(newFiles, this.diffs);
    }
    this.updateDiffCursor();
    this.diffCursor?.reInitAndUpdateStops();
  }

  /**
   * Given an array of paths and a NodeList of diff elements, render the diff
   * for each path in order, awaiting the previous render to complete before
   * continuing.
   *
   * private but used in test
   *
   * @param initialCount The total number of paths in the pass.
   */
  async renderInOrder(files: PatchSetFile[], diffElements: GrDiffHost[]) {
    this.reporting.time(Timing.FILE_EXPAND_ALL);

    for (const file of files) {
      const path = file.path;
      const diffElem = this.findDiffByPath(path, diffElements);
      if (!diffElem) {
        this.reporting.error(
          'GrFileList',
          new Error(`Did not find <gr-diff-host> element for ${path}`)
        );
        return;
      }
      diffElem.prefetchDiff();
    }

    await asyncForeach(files, async (file, cancel) => {
      const path = file.path;
      this.cancelForEachDiff = cancel;

      const diffElem = this.findDiffByPath(path, diffElements);
      if (!diffElem) {
        this.reporting.error(
          'GrFileList',
          new Error(`Did not find <gr-diff-host> element for ${path}`)
        );
        return;
      }
      if (!this.diffPrefs) {
        throw new Error('diffPrefs must be set');
      }

      // When one file is expanded individually then automatically mark as
      // reviewed, if the user's diff prefs request it. Doing this for
      // "Expand All" would not be what the user wants, because there is no
      // control over which diffs were actually seen. And for lots of diffs
      // that would even be a problem for write QPS quota.
      if (
        this.loggedIn &&
        !this.diffPrefs.manual_review &&
        files.length === 1
      ) {
        await this.reviewFile(path, true);
      }
      await diffElem.reload();
    });

    this.cancelForEachDiff = undefined;
    this.reporting.timeEnd(Timing.FILE_EXPAND_ALL, {
      count: files.length,
      height: this.clientHeight,
    });
    /*
    * Block diff cursor from auto scrolling after files are done rendering.
    * This prevents the bug where the screen jumps to the first diff chunk
    * after files are done being rendered after the user has already begun
    * scrolling.
    * This also however results in the fact that the cursor does not auto
    * focus on the first diff chunk on a small screen. This is however, a use
    * case we are willing to not support for now.

    * Using reInit resulted in diffCursor.row being set which
    * prevented the issue of scrolling to top when we expand the second
    * file individually.
    */
    this.diffCursor?.reInitAndUpdateStops();
  }

  /** Cancel the rendering work of every diff in the list */
  private cancelDiffs() {
    if (this.cancelForEachDiff) {
      this.cancelForEachDiff();
    }
  }

  /**
   * In the given NodeList of diff elements, find the diff for the given path.
   */
  private findDiffByPath(path: string, diffElements: GrDiffHost[]) {
    return diffElements.find(diff => diff.path === path);
  }

  /**
   * Compute size bar layout values from the file list.
   * Private but used in tests.
   */
  computeSizeBarLayout() {
    const stats: SizeBarLayout = createDefaultSizeBarLayout();
    this.files
      .slice(0, this.numFilesShown)
      .filter(f => !isMagicPath(f.__path))
      .forEach(f => {
        if (f.lines_inserted) {
          stats.maxInserted = Math.max(stats.maxInserted, f.lines_inserted);
        }
        if (f.lines_deleted) {
          stats.maxDeleted = Math.max(stats.maxDeleted, f.lines_deleted);
        }
      });
    const ratio = stats.maxInserted / (stats.maxInserted + stats.maxDeleted);
    if (!isNaN(ratio)) {
      stats.maxAdditionWidth =
        (SIZE_BAR_MAX_WIDTH - SIZE_BAR_GAP_WIDTH) * ratio;
      stats.maxDeletionWidth =
        SIZE_BAR_MAX_WIDTH - SIZE_BAR_GAP_WIDTH - stats.maxAdditionWidth;
      stats.additionOffset = stats.maxDeletionWidth + SIZE_BAR_GAP_WIDTH;
    }
    return stats;
  }

  /**
   * Get the width of the addition bar for a file.
   * Private but used in tests.
   */
  computeBarAdditionWidth(file?: NormalizedFileInfo, stats?: SizeBarLayout) {
    if (
      !file ||
      !stats ||
      stats.maxInserted === 0 ||
      !file.lines_inserted ||
      !!isMagicPath(file.__path)
    ) {
      return 0;
    }
    const width =
      (stats.maxAdditionWidth * file.lines_inserted) / stats.maxInserted;
    return width === 0 ? 0 : Math.max(SIZE_BAR_MIN_WIDTH, width);
  }

  /**
   * Get the x-offset of the addition bar for a file.
   * Private but used in tests.
   */
  computeBarAdditionX(stats: SizeBarLayout) {
    return stats.additionOffset;
  }

  /**
   * Get the width of the deletion bar for a file.
   * Private but used in tests.
   */
  computeBarDeletionWidth(file?: NormalizedFileInfo, stats?: SizeBarLayout) {
    if (
      !file ||
      !stats ||
      stats.maxDeleted === 0 ||
      !file.lines_deleted ||
      !!isMagicPath(file.__path)
    ) {
      return 0;
    }
    const width =
      (stats.maxDeletionWidth * file.lines_deleted) / stats.maxDeleted;
    return width === 0 ? 0 : Math.max(SIZE_BAR_MIN_WIDTH, width);
  }

  /**
   * Get the x-offset of the deletion bar for a file.
   * Private but used in tests.
   */
  computeBarDeletionX(file?: NormalizedFileInfo, stats?: SizeBarLayout) {
    if (!file || !stats) return;
    return stats.maxDeletionWidth - this.computeBarDeletionWidth(file, stats);
  }

  // Private but used in tests.
  computeSizeBarsClass(path?: string) {
    let hideClass = '';
    if (!this.showSizeBars) {
      hideClass = 'hide';
    } else if (isMagicPath(path)) {
      hideClass = 'invisible';
    }
    return `sizeBars ${hideClass}`;
  }

  /**
   * Shows registered dynamic columns iff the 'header', 'content' and
   * 'summary' endpoints are registered the exact same number of times.
   * Ideally, there should be a better way to enforce the expectation of the
   * dependencies between dynamic endpoints.
   */
  private computeShowDynamicColumns() {
    return !!(
      this.dynamicHeaderEndpoints &&
      this.dynamicContentEndpoints &&
      this.dynamicSummaryEndpoints &&
      this.dynamicHeaderEndpoints.length &&
      this.dynamicHeaderEndpoints.length ===
        this.dynamicContentEndpoints.length &&
      this.dynamicHeaderEndpoints.length === this.dynamicSummaryEndpoints.length
    );
  }

  /**
   * Shows registered dynamic prepended columns iff the 'header', 'content'
   * endpoints are registered the exact same number of times.
   */
  private computeShowPrependedDynamicColumns() {
    return !!(
      this.dynamicPrependedHeaderEndpoints &&
      this.dynamicPrependedContentEndpoints &&
      this.dynamicPrependedHeaderEndpoints.length &&
      this.dynamicPrependedHeaderEndpoints.length ===
        this.dynamicPrependedContentEndpoints.length
    );
  }

  /**
   * Returns true if none of the inline diffs have been expanded.
   * Private but used in tests.
   */
  noDiffsExpanded() {
    return this.filesExpanded === FilesExpandedState.NONE;
  }

  private getOldPath(file: NormalizedFileInfo) {
    // The gr-endpoint-decorator is waiting until all gr-endpoint-param
    // values are updated.
    // The old_path property is undefined for added files, and the
    // gr-endpoint-param value bound to file.old_path is never updates.
    // As a results, the gr-endpoint-decorator doesn't work for added files.
    // As a workaround, this method returns null instead of undefined.
    return file.old_path ?? null;
  }
}
