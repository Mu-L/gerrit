/**
 * @license
 * Copyright 2022 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import {
  BasePatchSetNum,
  ChangeInfo,
  EDIT,
  NumericChangeId,
  PARENT,
  PatchSetNumber,
  RepoName,
  RevisionPatchSetNum,
} from '../../api/rest-api';
import {Tab} from '../../constants/constants';
import {GerritView} from '../../services/router/router-model';
import {UrlEncodedCommentId} from '../../types/common';
import {assertIsDefined, toggleSet} from '../../utils/common-util';
import {select} from '../../utils/observable-util';
import {
  encodeURL,
  getBaseUrl,
  getPatchRangeExpression,
} from '../../utils/url-util';
import {AttemptChoice} from '../checks/checks-util';
import {define} from '../dependency';
import {Model} from '../base/model';
import {ViewState} from './base';
import {isNumber} from '../../utils/patch-set-util';

export enum ChangeChildView {
  OVERVIEW = 'OVERVIEW',
  DIFF = 'DIFF',
  EDIT = 'EDIT',
}

export interface ChangeViewState extends ViewState {
  view: GerritView.CHANGE;
  childView: ChangeChildView;

  changeNum: NumericChangeId;
  repo: RepoName;
  /** `undefined` means "latest". */
  patchNum?: RevisionPatchSetNum;
  /** `undefined` is treated the same as `ParentPatchSet`. */
  basePatchNum?: BasePatchSetNum;
  /** Refers to comment on COMMENTS tab in OVERVIEW. */
  commentId?: UrlEncodedCommentId;

  // TODO: Move properties that only apply to OVERVIEW into a submessage.

  edit?: boolean;
  /** This can be a string only for plugin provided tabs. */
  tab?: Tab | string;

  // TODO: Move properties that only apply to CHECKS tab into a submessage.

  /** Checks related view state */

  /**
   * Selected patchset for check runs. If not set, then `checksPatchset$`
   * emits `patchNum`. So when you initially load a URL without the
   * "checksPatchset" parameter being set, then `checksPatchset$` will emit
   * `patchNum`. And if you change the patchset choice in the file list, then
   * the "checksPatchset" parameter will be reset and again `checksPatchset$`
   * will emit `patchNum`.
   */
  checksPatchset?: PatchSetNumber;
  /** regular expression for filtering check runs */
  filter?: string;
  /** selected attempt for check runs (undefined=latest) */
  attempt?: AttemptChoice;
  /** selected check runs identified by `checkName` */
  checksRunsSelected?: Set<string>;
  /** regular expression for filtering check results */
  checksResultsFilter?: string;

  /** State properties that trigger one-time actions */

  /** for scrolling a Change Log message into view in gr-change-view */
  messageHash?: string;
  /**
   * For logging where the user came from. This is handled by the router, so
   * this is not inspected by the model.
   */
  usp?: string;
  /**
   * Triggers all change related data to be reloaded. This is implemented by
   * intercepting change view state updates and `forceReload` causing the view
   * state to be wiped clean as `undefined` in an intermediate update.
   */
  forceReload?: boolean;
  /** triggers opening the reply dialog */
  openReplyDialog?: boolean;

  /** These properties apply to the DIFF child view only. */
  diffView?: {
    path: string;
    // TODO: Use LineNumber as a type, i.e. accept FILE and LOST.
    lineNum?: number;
    leftSide?: boolean;
  };

  /** These properties apply to the EDIT child view only. */
  editView?: {
    path: string;
    lineNum?: number;
  };
}

export type DiffViewState = Partial<ChangeViewState> & {
  patchNum: RevisionPatchSetNum;
  diffView: {
    path: string;
    lineNum?: number;
    leftSide?: boolean;
  };
};

export type EditViewState = Partial<ChangeViewState> & {
  patchNum: RevisionPatchSetNum;
  editView: {
    path: string;
    lineNum?: number;
  };
};

/**
 * This is a convenience type such that you can pass a `ChangeInfo` object
 * as the `change` property instead of having to set both the `changeNum` and
 * `project` properties explicitly.
 */
export type CreateChangeUrlObject = Omit<
  ChangeViewState,
  'view' | 'childView' | 'changeNum' | 'repo'
> & {
  change: Pick<ChangeInfo, '_number' | 'project'>;
};

export function isCreateChangeUrlObject(
  state: CreateChangeUrlObject | Omit<ChangeViewState, 'view'>
): state is CreateChangeUrlObject {
  return !!(state as CreateChangeUrlObject).change;
}

export function objToState(
  obj:
    | (CreateChangeUrlObject & {childView: ChangeChildView})
    | Omit<ChangeViewState, 'view'>
): ChangeViewState {
  if (isCreateChangeUrlObject(obj)) {
    return {
      ...obj,
      view: GerritView.CHANGE,
      changeNum: obj.change._number,
      repo: obj.change.project,
    };
  }
  return {...obj, view: GerritView.CHANGE};
}

export function createChangeViewUrl(state: ChangeViewState): string {
  switch (state.childView) {
    case ChangeChildView.OVERVIEW:
      return createChangeUrl(state);
    case ChangeChildView.DIFF:
      return createDiffUrl(state);
    case ChangeChildView.EDIT:
      return createEditUrl(state);
  }
}

export function createChangeUrl(
  obj: CreateChangeUrlObject | Omit<ChangeViewState, 'view' | 'childView'>
): string {
  const state: ChangeViewState = objToState({
    ...obj,
    childView: ChangeChildView.OVERVIEW,
  });

  let suffix = '';
  const queries = [];
  if (
    state.checksPatchset &&
    state.checksPatchset > 0 &&
    state.patchNum !== state.checksPatchset
  ) {
    queries.push(`checksPatchset=${state.checksPatchset}`);
  }
  if (state.attempt) {
    if (state.attempt !== 'latest') queries.push(`attempt=${state.attempt}`);
  }
  if (state.filter) {
    queries.push(`filter=${state.filter}`);
  }
  if (state.checksResultsFilter) {
    queries.push(`checksResultsFilter=${state.checksResultsFilter}`);
  }
  if (state.checksRunsSelected && state.checksRunsSelected.size > 0) {
    queries.push(`checksRunsSelected=${[...state.checksRunsSelected].sort()}`);
  }
  if (state.tab && state.tab !== Tab.FILES) {
    queries.push(`tab=${state.tab}`);
  }
  if (state.forceReload) {
    queries.push('forceReload=true');
  }
  if (state.openReplyDialog) {
    queries.push('openReplyDialog=true');
  }
  if (state.usp) {
    queries.push(`usp=${state.usp}`);
  }
  if (state.edit) {
    suffix += ',edit';
  }
  if (state.commentId) {
    suffix += `/comments/${state.commentId}`;
  }
  if (queries.length > 0) {
    suffix += '?' + queries.join('&');
  }
  if (state.messageHash) {
    suffix += state.messageHash;
  }

  return `${createChangeUrlCommon(state)}${suffix}`;
}

export function createDiffUrl(
  obj: CreateChangeUrlObject | Omit<ChangeViewState, 'view' | 'childView'>
): string {
  const state: ChangeViewState = objToState({
    ...obj,
    childView: ChangeChildView.DIFF,
  });

  let path = `/${encodeURL(state.diffView?.path ?? '')}`;
  // TODO: Move creating of comment URLs to a separate function. We are
  // "abusing" the `commentId` property, which should only be used for pointing
  // to comment in the COMMENTS tab of the OVERVIEW page.
  if (state.commentId) {
    path += `comment/${state.commentId}/`;
  }

  let queryParams = '';
  const params = [];
  if (
    state.checksPatchset &&
    state.checksPatchset > 0 &&
    state.patchNum !== state.checksPatchset
  ) {
    params.push(`checksPatchset=${state.checksPatchset}`);
  }
  if (params.length > 0) {
    queryParams = '?' + params.join('&');
  }

  let hash = '';
  if (state.diffView?.lineNum) {
    hash += '#';
    if (state.diffView?.leftSide) {
      hash += 'b';
    }
    hash += state.diffView.lineNum;
  }

  return `${createChangeUrlCommon(state)}${path}${queryParams}${hash}`;
}

export function createEditUrl(
  obj: Omit<ChangeViewState, 'view' | 'childView'>
): string {
  const state: ChangeViewState = objToState({
    ...obj,
    childView: ChangeChildView.DIFF,
    patchNum: obj.patchNum ?? EDIT,
  });

  const path = `/${encodeURL(state.editView?.path ?? '')}`;
  const line = state.editView?.lineNum;
  const suffix = line ? `#${line}` : '';

  return `${createChangeUrlCommon(state)}${path},edit${suffix}`;
}

/**
 * The shared part of creating a change URL between OVERVIEW, DIFF and EDIT
 * child views.
 */
function createChangeUrlCommon(state: ChangeViewState) {
  let range = getPatchRangeExpression(state);
  if (range.length) range = '/' + range;

  let repo = '';
  if (state.repo) repo = `${encodeURL(state.repo)}/+/`;

  return `${getBaseUrl()}/c/${repo}${state.changeNum}${range}`;
}

export const changeViewModelToken =
  define<ChangeViewModel>('change-view-model');

export class ChangeViewModel extends Model<ChangeViewState | undefined> {
  public readonly changeNum$ = select(this.state$, state => state?.changeNum);

  public readonly patchNum$ = select(this.state$, state => state?.patchNum);

  public readonly basePatchNum$ = select(
    this.state$,
    state => state?.basePatchNum
  );

  public readonly openReplyDialog$ = select(
    this.state$,
    state => state?.openReplyDialog
  );

  public readonly commentId$ = select(this.state$, state => state?.commentId);

  public readonly edit$ = select(this.state$, state => !!state?.edit);

  public readonly editPath$ = select(
    this.state$,
    state => state?.editView?.path
  );

  public readonly diffPath$ = select(
    this.state$,
    state => state?.diffView?.path
  );

  public readonly diffLine$ = select(
    this.state$,
    state => state?.diffView?.lineNum
  );

  public readonly diffLeftSide$ = select(
    this.state$,
    state => state?.diffView?.leftSide ?? false
  );

  public readonly childView$ = select(this.state$, state => state?.childView);

  public readonly tab$ = select(this.state$, state => {
    if (state?.tab) return state.tab;
    if (state?.commentId) return Tab.COMMENT_THREADS;
    return Tab.FILES;
  });

  // See documentation for `checksPatchset` property.
  public readonly checksPatchset$ = select(this.state$, state => {
    if (state?.checksPatchset === undefined && state?.patchNum !== EDIT) {
      return state?.patchNum as PatchSetNumber;
    } else {
      return state?.checksPatchset;
    }
  });

  public readonly attempt$ = select(this.state$, state => state?.attempt);

  public readonly filter$ = select(this.state$, state => state?.filter);

  public readonly checksResultsFilter$ = select(
    this.state$,
    state => state?.checksResultsFilter ?? ''
  );

  public readonly checksRunsSelected$ = select(
    this.state$,
    state => state?.checksRunsSelected ?? new Set<string>()
  );

  constructor() {
    super(undefined);
    this.state$.subscribe(s => {
      if (s?.usp || s?.forceReload || s?.openReplyDialog) {
        this.updateState({
          usp: undefined,
          forceReload: undefined,
          openReplyDialog: undefined,
        });
      }
      if (s?.checksPatchset && s?.checksPatchset === s?.patchNum) {
        this.updateState({
          checksPatchset: undefined,
        });
      }
    });
    document.addEventListener('reload', this.reload);
  }

  override finalize(): void {
    document.removeEventListener('reload', this.reload);
  }

  /**
   * Calling this is the same as firing the 'reload' event. This is also the
   * same as adding `forceReload` parameter in the URL. See below.
   */
  reload = () => {
    const state = this.getState();
    if (state !== undefined) this.forceLoad(state);
  };

  /**
   * This is the destination of where the `reload()` method, the `reload` event
   * and the `forceReload` URL parameter all end up.
   */
  private forceLoad(state: ChangeViewState) {
    this.setState(undefined);
    // We have to do this in a timeout, because we need the `undefined` value to
    // be processed by all observers first and thus have the "reset" completed.
    setTimeout(() => this.setState({...state, forceReload: undefined}));
  }

  override setState(state: ChangeViewState | undefined): void {
    if (state?.forceReload) {
      this.forceLoad(state);
    } else {
      super.setState(state);
    }
  }

  /**
   * Wrapper around createDiffUrl() that falls back to the current state for all
   * properties that are not explicitly provided as an override.
   */
  diffUrl(override: DiffViewState): string {
    const current = this.getState();
    assertIsDefined(current?.changeNum);
    assertIsDefined(current?.repo);

    const patchNum = override.patchNum ?? current.patchNum;
    let basePatchNum = override.basePatchNum ?? current.basePatchNum;
    if (isNumber(basePatchNum) && isNumber(patchNum)) {
      if ((patchNum as number) <= (basePatchNum as number)) {
        basePatchNum = PARENT;
      }
    }
    return createDiffUrl({
      changeNum: override.changeNum ?? current.changeNum,
      repo: override.repo ?? current.repo,
      patchNum,
      basePatchNum,
      checksPatchset: override.checksPatchset ?? current.checksPatchset,
      diffView: override.diffView ?? current.diffView,
    });
  }

  /**
   * Wrapper around createEditUrl() that falls back to the current state for all
   * properties that are not explicitly provided as an override.
   */
  editUrl(override: EditViewState): string {
    const current = this.getState();
    assertIsDefined(current?.changeNum);
    assertIsDefined(current?.repo);

    return createEditUrl({
      changeNum: override.changeNum ?? current.changeNum,
      repo: override.repo ?? current.repo,
      patchNum: override.patchNum ?? current.patchNum,
      editView: override.editView ?? current.editView,
    });
  }

  toggleSelectedCheckRun(checkName: string) {
    const current = this.getState()?.checksRunsSelected ?? new Set();
    const next = new Set(current);
    toggleSet(next, checkName);
    this.updateState({checksRunsSelected: next});
  }
}
