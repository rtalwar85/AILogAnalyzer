# AI Coding Guidelines for first-react

## Project Overview
This is a minimal React application built with Vite, featuring React 19 and the React Compiler for optimized performance. The app consists of a single-page counter demo with hot module replacement (HMR) during development.

## Architecture
- **Entry Point**: `src/main.jsx` renders `<App />` into `#root` using React 18+ `createRoot`
- **Main Component**: `src/App.jsx` contains the app logic and JSX structure
- **Assets**: Static files in `src/assets/` (e.g., `react.svg`) and public root (e.g., `/vite.svg`)
- **Styling**: CSS modules via direct imports (`./App.css`, `./index.css`)

## Development Workflow
- **Start Dev Server**: `npm run dev` (Vite with HMR enabled)
- **Build for Production**: `npm run build` (outputs to `dist/`)
- **Lint Code**: `npm run lint` (ESLint flat config targeting `**/*.{js,jsx}`)
- **Preview Build**: `npm run preview` (serves `dist/` locally)

## Code Conventions
- **File Structure**: All React components and logic in `src/`, assets in `src/assets/`
- **Imports**: Use relative paths for local files (e.g., `import App from './App.jsx'`)
- **JSX**: Standard React JSX with hooks (e.g., `useState` from 'react')
- **ESLint Rules**: 
  - Ignores `dist/` directory
  - `no-unused-vars` allows uppercase vars (for React components)
  - Enforces React hooks rules and Vite refresh patterns

## Build Configuration
- **Vite Config**: `vite.config.js` enables React plugin with Babel and React Compiler
- **React Compiler**: Enabled via `babel-plugin-react-compiler` - optimizes component re-renders but may affect dev/build performance
- **ESLint Config**: `eslint.config.js` uses flat config with recommended rules plus React-specific plugins

## Key Patterns
- **Component Export**: Default export functions (e.g., `export default App`)
- **Asset Loading**: SVG imports as modules (e.g., `import reactLogo from './assets/react.svg'`)
- **State Management**: Simple `useState` for local state (no external state libs)

## Dependencies
- **Runtime**: React 19.2.0, React DOM 19.2.3
- **Build**: Vite 7.2.4 with React plugin
- **Linting**: ESLint 9.39.1 with React hooks and refresh plugins
- **Types**: @types/react and @types/react-dom (TypeScript-ready but not used)</content>
<parameter name="filePath">c:\React\first-react\.github\copilot-instructions.md