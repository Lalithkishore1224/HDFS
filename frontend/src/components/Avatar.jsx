/** The Google profile picture, or the person's initial when there is none. */
export function Avatar({ account, size = 32 }) {
  const initial = (account.name || account.email).trim().charAt(0).toUpperCase();
  const style = { width: size, height: size, fontSize: size * 0.45 };

  return account.pictureUrl ? (
    <img className="avatar" src={account.pictureUrl} alt="" style={style} referrerPolicy="no-referrer" />
  ) : (
    <span className="avatar avatar-initial" style={style} aria-hidden>
      {initial}
    </span>
  );
}
