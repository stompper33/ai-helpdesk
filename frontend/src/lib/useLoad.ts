// Load data for a component: { data, error, loading, reload }.
// Ignores responses that arrive after a newer request started (e.g. switching filters quickly).

import { useCallback, useEffect, useRef, useState } from "react";

export interface Loaded<T> {
  data: T | undefined;
  error: Error | undefined;
  loading: boolean;
  reload: () => Promise<void>;
  setData: (data: T) => void;
}

export function useLoad<T>(load: () => Promise<T>, deps: readonly unknown[]): Loaded<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<Error>();
  const [loading, setLoading] = useState(true);
  const latest = useRef(0);

  // `deps` decides when to load again (like useEffect's dependency list).
  const reload = useCallback(async () => {
    const request = ++latest.current;
    setLoading(true);
    try {
      const result = await load();
      if (request === latest.current) {
        setData(result);
        setError(undefined);
      }
    } catch (e) {
      if (request === latest.current) setError(e as Error);
    } finally {
      if (request === latest.current) setLoading(false);
    }
  }, deps);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { data, error, loading, reload, setData };
}
