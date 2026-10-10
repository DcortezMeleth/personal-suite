// TypeScript 7 rejects a side-effect import it has no declaration for
// (TS2882), and a stylesheet is exactly that. Needed per compilation rather
// than once in libs/ui: this ambient declaration has to be in scope when tsc
// checks the shared library's sources as they arrive through node_modules.
declare module "*.css";
