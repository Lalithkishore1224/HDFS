/** The person's initial in a black circle. */
export function Avatar({ account, size = 32 }) {
  const initial = (account.name || account.username).trim().charAt(0).toUpperCase();
  const style = { width: size, height: size, fontSize: size * 0.45 };

  return (
    <span className="avatar avatar-initial" style={style} aria-hidden>
      {initial}
    </span>
  );
}
