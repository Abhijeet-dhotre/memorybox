package com.muse.memorybox.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.memorybox.R;
import com.muse.memorybox.model.MemoryVideo;
import com.muse.memorybox.model.SearchHit;
import com.muse.memorybox.util.Texts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Home list: plain memories, or search results with tier + match info. */
public final class MemoryAdapter extends RecyclerView.Adapter<MemoryAdapter.Holder> {

    public interface OnItemClick {
        void onClick(MemoryVideo video, int timestampSeconds, View thumbView);
    }

    private final List<Object> items = new ArrayList<>(); // MemoryVideo | SearchHit
    private OnItemClick listener;
    private int lastAnimatedPosition = -1;

    public void setOnItemClick(OnItemClick listener) {
        this.listener = listener;
    }

    public void showMemories(List<MemoryVideo> videos) {
        items.clear();
        items.addAll(videos);
        lastAnimatedPosition = -1;
        notifyDataSetChanged();
    }

    public void showResults(List<SearchHit> hits) {
        items.clear();
        items.addAll(hits);
        lastAnimatedPosition = -1;
        notifyDataSetChanged();
    }

    public int size() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_memory, parent, false);
        Holder holder = new Holder(view);
        // Clickable EVERYWHERE: the listener lives on the card itself and
        // on every child view, and resolves the item at tap time — home
        // list and search results behave identically.
        View.OnClickListener click = v -> dispatchClick(holder);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(click);
        holder.thumb.setOnClickListener(click);
        holder.title.setOnClickListener(click);
        holder.summary.setOnClickListener(click);
        holder.match.setOnClickListener(click);
        holder.meta.setOnClickListener(click);
        holder.category.setOnClickListener(click);
        // Tactile iOS-style press: real spring physics on the whole card.
        IosSpringAnimator.attachPressSpring(view);
        return holder;
    }

    private void dispatchClick(Holder holder) {
        if (listener == null) return;
        int pos = holder.getBindingAdapterPosition();
        if (pos < 0 || pos >= items.size()) return;
        Object item = items.get(pos);
        if (item instanceof SearchHit) {
            SearchHit hit = (SearchHit) item;
            listener.onClick(hit.video, hit.matchingTimestampSeconds, holder.thumb);
        } else if (item instanceof MemoryVideo) {
            listener.onClick((MemoryVideo) item, 0, holder.thumb);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Object item = items.get(position);
        MemoryVideo video;
        SearchHit hit = null;
        if (item instanceof SearchHit) {
            hit = (SearchHit) item;
            video = hit.video;
        } else {
            video = (MemoryVideo) item;
        }
        holder.title.setText(video.title);
        holder.category.setText(video.category.toUpperCase(Locale.ROOT));
        holder.summary.setText(video.summary);
        ThumbnailLoader.load(holder.thumb, video.thumbnailUrl);

        if (hit != null) {
            holder.tier.setVisibility(View.VISIBLE);
            holder.tier.setText(hit.tier == 1 ? "Tier 1 · about this"
                    : hit.tier == 2 ? "Tier 2 · related" : "Weak match");
            holder.match.setVisibility(View.VISIBLE);
            holder.match.setText("“" + hit.matchingSentence + "”  ·  "
                    + Texts.formatTimestamp(hit.matchingTimestampSeconds)
                    + "  ·  score " + String.format(Locale.ROOT, "%.2f", hit.videoScore));
        } else {
            holder.tier.setVisibility(View.GONE);
            holder.match.setVisibility(View.GONE);
        }

        StringBuilder meta = new StringBuilder();
        meta.append(video.platform);
        if (!video.channel.isEmpty()) meta.append(" · ").append(video.channel);
        meta.append(" · ").append(video.dateSaved);
        meta.append(" · ").append(video.category);
        if (video.durationSeconds > 0) {
            meta.append(" · ").append(Texts.formatTimestamp(video.durationSeconds));
        }
        if (!video.language.isEmpty()) meta.append(" · ").append(video.language);
        holder.meta.setText(meta.toString());

        // Cascading spring entrance for items appearing for the first time.
        if (position > lastAnimatedPosition) {
            IosSpringAnimator.runCascadingSpringEntrance(holder.itemView, position);
            lastAnimatedPosition = position;
        }
        // Click handling is installed once in onCreateViewHolder and
        // resolves the tapped item at click time (dispatchClick).
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final android.widget.ImageView thumb;
        final TextView category;
        final TextView title;
        final TextView tier;
        final TextView summary;
        final TextView match;
        final TextView meta;

        Holder(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.itemThumb);
            category = itemView.findViewById(R.id.itemCategory);
            title = itemView.findViewById(R.id.itemTitle);
            tier = itemView.findViewById(R.id.itemTier);
            summary = itemView.findViewById(R.id.itemSummary);
            match = itemView.findViewById(R.id.itemMatch);
            meta = itemView.findViewById(R.id.itemMeta);
        }
    }
}
