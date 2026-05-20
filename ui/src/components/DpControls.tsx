import { useEffect, useState } from 'react';
import type { DpSchemaEntry } from '../api';

type Props = {
  schema: DpSchemaEntry[];
  values: Record<string, unknown>;
  pending: Record<string, boolean>;
  onChange: (index: string, value: unknown) => void;
};

export function DpControls({ schema, values, pending, onChange }: Props) {
  if (schema.length === 0) {
    return (
      <div className="muted">
        No DP schema defined. The raw values from the device are shown below.
      </div>
    );
  }
  return (
    <div>
      {schema.map((entry) => (
        <DpRow
          key={entry.index}
          entry={entry}
          value={values[entry.index]}
          isPending={pending[entry.index] === true}
          onChange={(v) => onChange(entry.index, v)}
        />
      ))}
    </div>
  );
}

type RowProps = {
  entry: DpSchemaEntry;
  value: unknown;
  isPending: boolean;
  onChange: (v: unknown) => void;
};

function DpRow({ entry, value, isPending, onChange }: RowProps) {
  return (
    <div className="dp-row">
      <div>
        <strong>{entry.label}</strong>
        <span className="muted" style={{ marginLeft: 8 }}>DP {entry.index}</span>
        {isPending && <span className="muted" style={{ marginLeft: 8 }}>sending…</span>}
      </div>
      <div>
        <Control entry={entry} value={value} isPending={isPending} onChange={onChange} />
      </div>
    </div>
  );
}

function Control({ entry, value, isPending, onChange }: RowProps) {
  switch (entry.kind) {
    case 'bool': {
      const on = value === true;
      return (
        <span
          className={`toggle${on ? ' on' : ''}`}
          onClick={() => !isPending && onChange(!on)}
          aria-disabled={isPending}
          style={isPending ? { opacity: 0.6 } : undefined}
        />
      );
    }
    case 'number':
      return <DebouncedNumberInput value={value} entry={entry} onCommit={onChange} />;
    case 'enum':
      return (
        <select
          value={(value as string) ?? ''}
          onChange={(e) => onChange(e.target.value)}
          style={{ width: 180 }}
        >
          <option value="" disabled>— select —</option>
          {(entry.options ?? []).map((opt) => (
            <option key={opt} value={opt}>{opt}</option>
          ))}
        </select>
      );
    case 'string':
      return <DebouncedTextInput value={value} onCommit={onChange} />;
  }
}

/**
 * Number input that maintains its OWN draft state and commits to the parent only
 * on blur, Enter, or the up/down buttons — so typing multiple digits stays focused
 * and doesn't fire a request per keystroke. The display syncs back from the
 * parent value whenever it changes externally (and the field isn't being edited).
 */
function DebouncedNumberInput({
  value, entry, onCommit,
}: { value: unknown; entry: DpSchemaEntry; onCommit: (v: number) => void }) {
  const [draft, setDraft] = useState<string>(numberToString(value));
  const [editing, setEditing] = useState(false);

  useEffect(() => {
    if (!editing) setDraft(numberToString(value));
  }, [value, editing]);

  const commit = () => {
    setEditing(false);
    const trimmed = draft.trim();
    if (trimmed === '') return;
    const n = Number(trimmed);
    if (Number.isFinite(n) && n !== Number(value)) onCommit(n);
  };

  return (
    <input
      type="number"
      value={draft}
      min={entry.min}
      max={entry.max}
      onFocus={() => setEditing(true)}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') (e.target as HTMLInputElement).blur();
        else if (e.key === 'Escape') {
          setDraft(numberToString(value));
          setEditing(false);
          (e.target as HTMLInputElement).blur();
        }
      }}
      style={{ width: 120 }}
    />
  );
}

function DebouncedTextInput({
  value, onCommit,
}: { value: unknown; onCommit: (v: string) => void }) {
  const [draft, setDraft] = useState<string>((value as string) ?? '');
  const [editing, setEditing] = useState(false);

  useEffect(() => {
    if (!editing) setDraft((value as string) ?? '');
  }, [value, editing]);

  return (
    <input
      type="text"
      value={draft}
      onFocus={() => setEditing(true)}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={() => {
        setEditing(false);
        if (draft !== ((value as string) ?? '')) onCommit(draft);
      }}
      onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }}
      style={{ width: 220 }}
    />
  );
}

function numberToString(v: unknown): string {
  if (v === null || v === undefined) return '';
  if (typeof v === 'number') return String(v);
  if (typeof v === 'string') return v;
  return '';
}
