import { Component, createElement, render, h } from 'preact';
import fuzzysort from 'fuzzysort';

function isSameProject(p1, p2) {
  // I can't believe you have to do this
  return (p1.group_id == p2.group_id && p1.artifact_id == p2.artifact_id) //&& p1.version == p2.version
}

function parseCljdocURI(uri) {
  const splitted = uri.split('/')
  if (splitted.length >= 5 && splitted[1] == 'd') {
    return {group_id: splitted[2],
            artifact_id: splitted[3],
            version: splitted[4]}
  }
}

function trackProjectOpened() {
  const maxTrackedCount = 15
  const project = parseCljdocURI(window.location.pathname);
  if (project) {
    var previouslyOpened = JSON.parse(localStorage.getItem('previouslyOpened')) || [];
    // remove identical values
    previouslyOpened = previouslyOpened.filter(p => !(isSameProject(p, project)))
    previouslyOpened.push(project)
    // truncate from the front to not have localstorage grow too large
    if (previouslyOpened.length > maxTrackedCount) {
      previouslyOpened = previouslyOpened.slice(previouslyOpened.length - maxTrackedCount, previouslyOpened.length)
    }
    localStorage.setItem('previouslyOpened', JSON.stringify(previouslyOpened))
  }
}

class SwitcherSingleResultView extends Component {
  // TODO properly scroll list items into view
  // - should only scroll if necessary
  // - should only scroll minimum distance
  componentDidUpdate (prevProps, prevState) {}

  render (props, state) {
    let r = props.r
    let isSelected = props.isSelected
    const project = (r.group_id === r.artifact_id ? r.group_id : r.group_id + '/' + r.artifact_id)
    const docsUri = '/d/' + r.group_id + '/' + r.artifact_id + '/' + r.version
    return h('a', { className: 'no-underline black', href: docsUri , ref: n => this.domNode = n }, [
      h('div', { className: isSelected ? 'pa3 bb b--light-gray bg-washed-blue' : 'pa3 bb b--light-gray' }, [
        h('h4', { className: 'dib ma0' }, [
          project,
          h('span', { className: 'ml2 gray normal' }, r.version)]),
        h('a', {
          className: 'link blue ml2',
          href: docsUri
        }, 'view docs')
      ])
    ])
  }
}

const SwitcherResultsView = (props) => {
  return h('div', {
    className: 'bg-white br1 br--bottom bb bl br b--blue overflow-y-scroll',
    style: { top: '2.3rem', maxHeight: '20rem' }
  }, props.results.map((r,idx) => h(SwitcherSingleResultView, {r: r, isSelected: props.selectedIndex == idx})))
}

class Switcher extends Component {
  handleKeyDown(e) {
    // If target is document body, trigger  on key `cmd+k` for MacOs `ctrl+k` otherwise
    let isMac = navigator.platform.toUpperCase().indexOf('MAC') >= 0;
    let switcherShortcut;

    if (e.which === 75) {
      if (isMac && e.metaKey || !isMac && e.ctrlKey) {
        switcherShortcut = true;
      }
    }

    if (switcherShortcut && e.target == document.body) {
      this.setState({show: true, results: this.state.previouslyOpened});
    } else if (e.which == 27) {
      this.setState({show: false, results: null});
    }
  }

  handleInputKeyUp(e) {
    if (e.which == 13) {
      const r = this.state.results[this.state.selectedIndex]
      window.location = '/d/' + r.group_id + '/' + r.artifact_id + '/' + r.version
    } else if (e.which == 38) { //arrow up
      this.setState({selectedIndex: Math.max(this.state.selectedIndex - 1, 0)})
    } else if (e.which == 40) { // arrow down
      this.setState({selectedIndex: Math.min(this.state.selectedIndex + 1, this.state.results.length - 1)})
    }
  }

  updateResults(searchStr) {
    if (searchStr == '') {
      this.initializeState()
    } else {
      let fuzzysortOptions = {allowTypo: false,
                              key: 'project_id'
                              //keys: ['group_id', 'artifact_id']
                             }
      let results = fuzzysort.go(searchStr, this.state.previouslyOpened, fuzzysortOptions)
      this.setState({results: results.map(r => r.obj),
                     selectedIndex: 0})
    }
  }

  constructor() {
    super()
    this.handleKeyDown = this.handleKeyDown.bind(this)
    this.handleInputKeyUp = this.handleInputKeyUp.bind(this)
    this.updateResults = this.updateResults.bind(this)
  }

  initializeState() {
    let previouslyOpened = JSON.parse(localStorage.getItem('previouslyOpened')) || []
    previouslyOpened.forEach(r => r.project_id = (r.group_id == r.artifact_id ? r.group_id : r.group_id + '/' + r.artifact_id))
    this.setState({previouslyOpened: previouslyOpened,
                   // for initial results we don't care about the most recently
                   // inserted one as that's the one we're looking at. Also
                   // we want them reversed to have the most recent shown first.
                   // TODO filter out current project entirely
                   selectedIndex: 0,
                   results: previouslyOpened.slice(0, -1).reverse()})
  }

  componentDidMount() {
    document.addEventListener('keydown', this.handleKeyDown)
    this.initializeState()
  }

  componentDidUpdate() {
    console.log(this.state.selectedIndex)
    if (this.state.show) {
      this.inputNode.focus()
    }
  }

  render (props,state) {
    if (state.show === true) {
      return h('div',
               {className: 'bg-black-30 fixed top-0 right-0 bottom-0 left-0 sans-serif',
                ref: node => this.backgroundNode = node,
                onClick: e => e.target == this.backgroundNode ? this.setState({show: false}) : null},
               h('div',
                 {className: 'mw7 center mt6 bg-white pa3 br2 shadow-3'},
                 h('input', {
                   placeHolder: 'Jump to recently viewed docs...',
                   className: 'pa2 w-100 br1 border-box b--blue ba input-reset',
                   ref: node => this.inputNode = node,
                   onKeyUp: e => this.handleInputKeyUp(e),
                   onInput: e => this.updateResults(e.target.value)
                 }),
                 state.results.length > 0 ? h(SwitcherResultsView, {results: state.results, selectedIndex: state.selectedIndex}) : null
                ))
    } else {
      return null
    }
  }
}

export { trackProjectOpened, Switcher };
