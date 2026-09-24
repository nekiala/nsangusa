export function deferred<T>() {
  let resolve!: (_value: T) => void;
  let reject!: (_reason: unknown) => void;
  const promise = new Promise<T>((accept, decline) => { resolve = accept; reject = decline; });
  return { promise, resolve, reject };
}
