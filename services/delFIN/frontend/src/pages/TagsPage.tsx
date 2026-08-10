import { useCallback, useEffect, useState } from "react";
import { DataCard, AlertBanner } from "@delfin/ui";
import { TagFormModal } from "../components/TagFormModal";
import { api, Tag, TagForm } from "../api/client";

export function TagsPage() {
  const [tags, setTags] = useState<Tag[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [editingTag, setEditingTag] = useState<Tag | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setTags(await api.get<Tag[]>("/tags"));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load tags");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  async function handleCreate(form: TagForm) {
    try {
      await api.post("/tags", form);
      setShowForm(false);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create tag");
    }
  }

  async function handleUpdate(form: TagForm) {
    if (!editingTag) return;
    try {
      await api.put(`/tags/${editingTag.id}`, form);
      setEditingTag(null);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to update tag");
    }
  }

  async function handleDelete(tag: Tag) {
    if (!window.confirm(`Delete tag "${tag.name}"? This removes it from any transactions it's on.`)) return;
    try {
      await api.del(`/tags/${tag.id}`);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to delete tag");
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-neutral-900">Tags</h1>
        <button
          onClick={() => setShowForm(true)}
          className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700"
        >
          + New Tag
        </button>
      </div>

      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}

      <DataCard title={`${tags.length} tags`}>
        {loading ? (
          <p className="py-6 text-center text-sm text-neutral-500">Loading…</p>
        ) : tags.length === 0 ? (
          <p className="py-6 text-center text-sm text-neutral-500">
            No tags yet. Tags let you track spend across a trip or event that spans several categories —
            assign them in bulk from the Transactions page.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-left text-xs font-semibold uppercase tracking-wide text-neutral-500">
                  <th className="py-2 pr-4">Tag</th>
                  <th className="py-2 text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {tags.map((tag) => (
                  <tr key={tag.id} className="border-b border-neutral-100 last:border-0 hover:bg-neutral-50">
                    <td className="py-2 pr-4">
                      <span
                        className="inline-block rounded-full px-2 py-0.5 text-xs font-medium text-white"
                        style={{ backgroundColor: tag.color }}
                      >
                        {tag.name}
                      </span>
                    </td>
                    <td className="py-2 text-right">
                      <div className="flex justify-end gap-2">
                        <button
                          onClick={() => setEditingTag(tag)}
                          className="rounded-md border border-neutral-300 bg-white px-2.5 py-1 text-xs font-medium text-neutral-700 hover:bg-neutral-50"
                        >
                          Edit
                        </button>
                        <button
                          onClick={() => handleDelete(tag)}
                          className="rounded-md border border-red-200 bg-white px-2.5 py-1 text-xs font-medium text-red-600 hover:bg-red-50"
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </DataCard>

      {showForm && <TagFormModal onSave={handleCreate} onClose={() => setShowForm(false)} />}
      {editingTag && (
        <TagFormModal initial={editingTag} onSave={handleUpdate} onClose={() => setEditingTag(null)} />
      )}
    </div>
  );
}
