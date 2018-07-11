const { createElement, render, Component } = preact // or React
const h = createElement

function debounced (delay, fn) {
  let timerId
  return function (...args) {
    if (timerId) {
      clearTimeout(timerId)
    }
    timerId = setTimeout(() => {
      fn(...args)
      timerId = null
    }, delay)
  }
}

const loadResults = (str, cb) => {
  const uri = 'https://clojars.org/search?q=' + str + '&format=json'
  fetch(uri)
    .then(response => response.json())
    .then(json => cb(json.results))
}

const SearchInput = (props) => {
  const debouncedLoader = debounced(300, loadResults)
  return h('input', {
    placeHolder: 'NEW! Jump to docs...',
    className: 'pa2 w-100 border-box b--blue ba input-reset',
    onFocus: e => props.focus(),
    onBlur: e => props.unfocus(),
    onKeyUp: e => (e.keyCode == 27 ? props.unfocus() : null),
    onInput: e => debouncedLoader(e.target.value, props.newResultsCallback)
  })
}

const SingleResultView = r => {
  const project = (r.group_name === r.jar_name ? r.group_name : r.group_name + '/' + r.jar_name)
  const docsUri = '/d/' + r.group_name + '/' + r.jar_name + '/' + r.version
  return h('a', { className: 'no-underline black', href: docsUri }, [
    h('div', { className: 'pa2 mv2 ba b--light-gray' }, [
      h('h4', { className: 'dib ma0' }, [
        project,
        h('span', { className: 'ml2 gray normal' }, r.version)]),
      h('a', {
        className: 'link blue ml2',
        href: docsUri
      }, 'view docs')
      // h('span', {}, r.created)
    ])
  ])
}

const ResultsView = (props) => {
  return h('div', {
    className: 'bg-white absolute w-100 overflow-y-scroll',
    style: { top: '2.3rem', maxHeight: '20rem' }
  }, props.results.sort((a, b) => b.created - a.created).map(r => SingleResultView(r)))
}

class App extends Component {
  constructor (props) {
    super(props)
    this.state = { results: [], focused: false }
    // loadResults('reagent', rs => this.setState({results: rs}))
  }

  render (props, state) {
    return h('div', { className: 'relative system-sans-serif' }, [
      h(SearchInput, { newResultsCallback: rs => this.setState({focused: true, results: rs}),
                       focus: () => this.setState({focused: true}),
                       unfocus: () => this.setState({focused: false}),}),
      (state.focused ? h(ResultsView, { results: state.results }) : null)
    ])
  }
}

render(h(App), document.querySelector('#cljdoc-search'))
